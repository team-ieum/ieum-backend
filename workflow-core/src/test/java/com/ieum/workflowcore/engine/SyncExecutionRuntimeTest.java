package com.ieum.workflowcore.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.config.RetryProperties;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.engine.executor.NodeExecutor;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class SyncExecutionRuntimeTest {

    private WorkflowExecutionLogRepository logRepository;
    private WorkflowExecutionRepository executionRepository;
    private WorkflowCrudService crudService;
    private ExecutionEventPublisher eventPublisher;
    private WorkflowExecution execution;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID executionId = UUID.randomUUID();

    /** 실행된 노드 ID를 기록하는 페이크 Executor. 공유 상태로 fan-out/조건/실패를 검증한다. */
    static class RecordingExecutor implements NodeExecutor {
        private final NodeType type;
        private final ConcurrentLinkedQueue<String> log;
        private final Set<String> failNodeIds;
        private final Map<String, Boolean> conditionResults;

        RecordingExecutor(NodeType type, ConcurrentLinkedQueue<String> log,
                          Set<String> failNodeIds, Map<String, Boolean> conditionResults) {
            this.type = type;
            this.log = log;
            this.failNodeIds = failNodeIds;
            this.conditionResults = conditionResults;
        }

        @Override
        public NodeType getNodeType() {
            return type;
        }

        @Override
        public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
            log.add(node.getId());
            if (failNodeIds.contains(node.getId())) {
                return ExecutorResult.failure("강제 실패: " + node.getId(), 1);
            }
            if (conditionResults.containsKey(node.getId())) {
                return ExecutorResult.success(Map.of("result", conditionResults.get(node.getId())), 1);
            }
            if (type == NodeType.AI) {
                return ExecutorResult.success(Map.of("output", node.getId()), 1,
                    new ExecutorResult.TokenUsage(120, 30, 150));
            }
            return ExecutorResult.success(Map.of("output", node.getId()), 1);
        }
    }

    private ConcurrentLinkedQueue<String> log;
    private Set<String> failNodeIds;
    private Map<String, Boolean> conditionResults;

    @BeforeEach
    void setUp() {
        logRepository = mock(WorkflowExecutionLogRepository.class);
        executionRepository = mock(WorkflowExecutionRepository.class);
        crudService = mock(WorkflowCrudService.class);
        eventPublisher = mock(ExecutionEventPublisher.class);
        execution = mock(WorkflowExecution.class);
        Workflow workflow = mock(Workflow.class);
        when(workflow.getUserId()).thenReturn(UUID.randomUUID());
        when(execution.getWorkflow()).thenReturn(workflow);
        when(execution.getStatus()).thenReturn(ExecutionStatus.RUNNING);
        when(execution.getTraceId()).thenReturn("11112222333344445555666677778888");
        when(executionRepository.findWithWorkflowById(executionId)).thenReturn(Optional.of(execution));

        log = new ConcurrentLinkedQueue<>();
        failNodeIds = new java.util.HashSet<>();
        conditionResults = new HashMap<>();
    }

    private SyncExecutionRuntime runtime() {
        List<NodeExecutor> executors = List.of(
            new RecordingExecutor(NodeType.TRIGGER, log, failNodeIds, conditionResults),
            new RecordingExecutor(NodeType.AI, log, failNodeIds, conditionResults),
            new RecordingExecutor(NodeType.CONDITION, log, failNodeIds, conditionResults)
        );
        SyncExecutionRuntime runtime = new SyncExecutionRuntime(
            objectMapper, logRepository, executionRepository, crudService, eventPublisher, executors,
            new RetryProperties());
        runtime.initExecutorMap();
        ReflectionTestUtils.setField(runtime, "parallelism", 4);
        return runtime;
    }

    private Map<String, Object> node(String id, String type) {
        Map<String, Object> n = new HashMap<>();
        n.put("id", id);
        n.put("type", type);
        n.put("label", id);
        n.put("config", new HashMap<>());
        return n;
    }

    private Map<String, Object> edge(String source, String target, String conditionType) {
        Map<String, Object> e = new HashMap<>();
        e.put("source", source);
        e.put("target", target);
        e.put("conditionType", conditionType);
        return e;
    }

    private void stubDefinition(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        WorkflowDefinitionDocument doc = WorkflowDefinitionDocument.builder()
            .nodes(nodes).edges(edges).build();
        when(crudService.loadDefinition(any())).thenReturn(doc);
    }

    private void run() throws Exception {
        runtime().execute(mock(WorkflowVersion.class), executionId, new HashMap<>());
    }

    @Test
    @DisplayName("fan-out: 한 노드의 두 분기 모두 실행된다")
    void fanout_runs_all_branches() throws Exception {
        // t -> a, a -> b, a -> c
        stubDefinition(
            List.of(node("t", "TRIGGER"), node("a", "AI"), node("b", "AI"), node("c", "AI")),
            List.of(edge("t", "a", null), edge("a", "b", null), edge("a", "c", null))
        );
        run();
        assertThat(log).containsExactlyInAnyOrder("t", "a", "b", "c");
        verify(execution).complete();
    }

    @Test
    @DisplayName("fan-in: 두 부모가 모두 끝난 뒤 합류 노드가 1회 실행된다")
    void fanin_waits_for_all_parents() throws Exception {
        // t -> a, t -> b, a -> c, b -> c
        stubDefinition(
            List.of(node("t", "TRIGGER"), node("a", "AI"), node("b", "AI"), node("c", "AI")),
            List.of(edge("t", "a", null), edge("t", "b", null), edge("a", "c", null), edge("b", "c", null))
        );
        run();
        List<String> executed = new ArrayList<>(log);
        assertThat(executed).containsExactlyInAnyOrder("t", "a", "b", "c");
        // c는 정확히 1회, 그리고 a·b 이후에 실행
        assertThat(executed.stream().filter("c"::equals).count()).isEqualTo(1);
        assertThat(executed.indexOf("c")).isGreaterThan(executed.indexOf("a"));
        assertThat(executed.indexOf("c")).isGreaterThan(executed.indexOf("b"));
    }

    @Test
    @DisplayName("CONDITION: 매칭 분기만 실행되고 반대 분기는 가지치기된다")
    void condition_prunes_dead_branch() throws Exception {
        conditionResults.put("cond", true);
        // t -> cond, cond--true-->yes, cond--false-->no
        stubDefinition(
            List.of(node("t", "TRIGGER"), node("cond", "CONDITION"), node("yes", "AI"), node("no", "AI")),
            List.of(edge("t", "cond", null), edge("cond", "yes", "true"), edge("cond", "no", "false"))
        );
        run();
        assertThat(log).contains("t", "cond", "yes");
        assertThat(log).doesNotContain("no");
        verify(execution).complete();
    }

    @Test
    @DisplayName("실패 시 전체 중단: 실패 노드 이후 분기는 실행되지 않고 FAILED 처리")
    void failure_stops_workflow() throws Exception {
        failNodeIds.add("a");
        // t -> a -> b
        stubDefinition(
            List.of(node("t", "TRIGGER"), node("a", "AI"), node("b", "AI")),
            List.of(edge("t", "a", null), edge("a", "b", null))
        );
        run();
        assertThat(log).contains("t", "a");
        assertThat(log).doesNotContain("b");
        // 재시도 대상이 아닌 실패(UNKNOWN, attempt=1)이므로 소진 아님
        verify(execution).fail(false);
        verify(execution, never()).complete();
    }

    @Test
    @DisplayName("node_runs에 실행 traceId가 함께 저장된다")
    void saveLog_includesTraceId() throws Exception {
        stubDefinition(
            List.of(node("t", "TRIGGER"), node("a", "AI")),
            List.of(edge("t", "a", null))
        );
        run();

        ArgumentCaptor<com.ieum.workflowcore.domain.WorkflowExecutionLog> captor =
            ArgumentCaptor.forClass(com.ieum.workflowcore.domain.WorkflowExecutionLog.class);
        verify(logRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
            .allMatch(l -> "11112222333344445555666677778888".equals(l.getTraceId()));
    }

    @Test
    @DisplayName("AI 노드는 usage 3필드가 node_runs에 저장되고, 비AI 노드는 null이다")
    void saveLog_includesUsage() throws Exception {
        stubDefinition(
            List.of(node("t", "TRIGGER"), node("a", "AI")),
            List.of(edge("t", "a", null))
        );
        run();

        ArgumentCaptor<com.ieum.workflowcore.domain.WorkflowExecutionLog> captor =
            ArgumentCaptor.forClass(com.ieum.workflowcore.domain.WorkflowExecutionLog.class);
        verify(logRepository, times(2)).save(captor.capture());

        com.ieum.workflowcore.domain.WorkflowExecutionLog aiLog = captor.getAllValues().stream()
            .filter(l -> "a".equals(l.getNodeId())).findFirst().orElseThrow();
        assertThat(aiLog.getPromptTokens()).isEqualTo(120);
        assertThat(aiLog.getCompletionTokens()).isEqualTo(30);
        assertThat(aiLog.getTotalTokens()).isEqualTo(150);

        com.ieum.workflowcore.domain.WorkflowExecutionLog triggerLog = captor.getAllValues().stream()
            .filter(l -> "t".equals(l.getNodeId())).findFirst().orElseThrow();
        assertThat(triggerLog.getTotalTokens()).isNull();
    }

    @Test
    @DisplayName("순환 그래프는 실행 전에 예외로 차단된다")
    void cycle_is_rejected() {
        // a -> b -> a (사이클)
        stubDefinition(
            List.of(node("a", "TRIGGER"), node("b", "AI")),
            List.of(edge("a", "b", null), edge("b", "a", null))
        );
        assertThatThrownBy(this::run)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("순환");
    }

    @Nested
    @DisplayName("RetryPolicy.backoffMillis")
    class BackoffMillisTests {

        private final RandomGenerator random = RandomGenerator.getDefault();

        @Test
        @DisplayName("jitter off: 지수 백오프가 그대로 반환된다 (1000 → 2000 → 4000)")
        void exponential_backoff_without_jitter() {
            RetryPolicy policy = new RetryPolicy(
                5, 1000L, 2.0, 30_000L, false, null, List.of(), IdempotencyMode.NONE);
            assertThat(policy.backoffMillis(1, random)).isEqualTo(1000L);
            assertThat(policy.backoffMillis(2, random)).isEqualTo(2000L);
            assertThat(policy.backoffMillis(3, random)).isEqualTo(4000L);
        }

        @Test
        @DisplayName("maxBackoffMs로 상한 clamp된다")
        void clamps_to_max_backoff() {
            RetryPolicy policy = new RetryPolicy(
                10, 1000L, 2.0, 3000L, false, null, List.of(), IdempotencyMode.NONE);
            assertThat(policy.backoffMillis(5, random)).isEqualTo(3000L);
        }

        @Test
        @DisplayName("jitter on: nextLong(0, computed+1)을 호출하고 그 반환값을 그대로 쓴다")
        void full_jitter_delegates_to_random() {
            RetryPolicy policy = new RetryPolicy(
                5, 1000L, 2.0, 30_000L, true, null, List.of(), IdempotencyMode.NONE);
            StubRandom stub = new StubRandom(777L);
            long waitMs = policy.backoffMillis(2, stub);   // computed = 2000
            assertThat(stub.capturedOrigin).isEqualTo(0L);
            assertThat(stub.capturedBound).isEqualTo(2001L);
            assertThat(waitMs).isEqualTo(777L);
        }

        @Test
        @DisplayName("jitter on이어도 computed=0이면 nextLong 호출 없이 0을 반환한다")
        void full_jitter_zero_computed_skips_random_call() {
            RetryPolicy policy = new RetryPolicy(
                5, 0L, 2.0, 30_000L, true, null, List.of(), IdempotencyMode.NONE);
            StubRandom stub = new StubRandom(999L);
            long waitMs = policy.backoffMillis(1, stub);
            assertThat(waitMs).isEqualTo(0L);
            assertThat(stub.capturedBound).isEqualTo(-1L);   // nextLong(origin, bound) 미호출
        }
    }

    /** {@link RetryPolicy#backoffMillis}가 넘기는 nextLong(origin, bound) 인자와 반환값 위임을 검증하는 스텁. */
    static class StubRandom implements RandomGenerator {
        private final long toReturn;
        long capturedOrigin = -1;
        long capturedBound = -1;

        StubRandom(long toReturn) {
            this.toReturn = toReturn;
        }

        @Override
        public long nextLong() {
            return toReturn;
        }

        @Override
        public long nextLong(long origin, long bound) {
            capturedOrigin = origin;
            capturedBound = bound;
            return toReturn;
        }
    }

    /**
     * 노드ID별로 몇 번째 시도에서 성공할지, 그 전엔 어떤 {@link FailureKind}로 실패할지 스크립트로
     * 정의하는 fake AI executor. 스크립트가 없는 노드는 첫 시도에 바로 성공한다(후속 노드용).
     */
    static class RetryScriptExecutor implements NodeExecutor {
        private final Map<String, Integer> succeedOnAttempt;
        private final Map<String, FailureKind> failureKind;
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        RetryScriptExecutor(Map<String, Integer> succeedOnAttempt, Map<String, FailureKind> failureKind) {
            this.succeedOnAttempt = succeedOnAttempt;
            this.failureKind = failureKind;
        }

        @Override
        public NodeType getNodeType() {
            return NodeType.AI;
        }

        @Override
        public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
            int attempt = calls.computeIfAbsent(node.getId(), k -> new AtomicInteger()).incrementAndGet();
            if (!succeedOnAttempt.containsKey(node.getId()) && !failureKind.containsKey(node.getId())) {
                return ExecutorResult.success(Map.of("output", node.getId()), 1);
            }
            Integer succeedAt = succeedOnAttempt.get(node.getId());
            if (succeedAt != null && attempt >= succeedAt) {
                return ExecutorResult.success(Map.of("output", node.getId()), 1);
            }
            return ExecutorResult.failure("스크립트 실패: " + node.getId(), 1,
                failureKind.getOrDefault(node.getId(), FailureKind.UNKNOWN));
        }

        int callCount(String nodeId) {
            return calls.getOrDefault(nodeId, new AtomicInteger()).get();
        }
    }

    @Nested
    @DisplayName("노드 재시도 실행")
    class RetryExecution {

        private RetryProperties retryProperties;

        @BeforeEach
        void setUpRetry() {
            retryProperties = new RetryProperties();
            // 테스트가 실제로 자지 않도록 백오프를 0으로 고정 (jitter on이어도 computed=0이면 0 반환)
            retryProperties.setBackoffMs(0);
            retryProperties.setMaxBackoffMs(0);
        }

        private SyncExecutionRuntime retryRuntime(NodeExecutor... executors) {
            SyncExecutionRuntime runtime = new SyncExecutionRuntime(
                objectMapper, logRepository, executionRepository, crudService, eventPublisher,
                List.of(executors), retryProperties);
            runtime.initExecutorMap();
            ReflectionTestUtils.setField(runtime, "parallelism", 4);
            return runtime;
        }

        @Test
        @DisplayName("2회차에 성공하면 워크플로우가 계속 진행되고 후속 노드도 실행된다")
        void succeeds_on_second_attempt() throws Exception {
            RetryScriptExecutor ai = new RetryScriptExecutor(
                Map.of("a", 2), Map.of("a", FailureKind.RATE_LIMIT));
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI"), node("b", "AI")),
                List.of(edge("t", "a", null), edge("a", "b", null))
            );
            retryRuntime(new RecordingExecutor(NodeType.TRIGGER, log, failNodeIds, conditionResults), ai)
                .execute(mock(WorkflowVersion.class), executionId, new HashMap<>());

            assertThat(ai.callCount("a")).isEqualTo(2);
            assertThat(ai.callCount("b")).isEqualTo(1);
            verify(execution).complete();
        }

        @Test
        @DisplayName("CLIENT_ERROR로 실패하는 노드는 1회만 실행되고 attempt_count=1, retry_exhausted=false")
        void client_error_is_not_retried() throws Exception {
            RetryScriptExecutor ai = new RetryScriptExecutor(
                Map.of(), Map.of("a", FailureKind.CLIENT_ERROR));
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI")),
                List.of(edge("t", "a", null))
            );
            retryRuntime(new RecordingExecutor(NodeType.TRIGGER, log, failNodeIds, conditionResults), ai)
                .execute(mock(WorkflowVersion.class), executionId, new HashMap<>());

            assertThat(ai.callCount("a")).isEqualTo(1);
            verify(execution).fail(false);
            verify(execution, never()).complete();

            ArgumentCaptor<com.ieum.workflowcore.domain.WorkflowExecutionLog> captor =
                ArgumentCaptor.forClass(com.ieum.workflowcore.domain.WorkflowExecutionLog.class);
            verify(logRepository, times(2)).save(captor.capture());
            assertThat(captor.getAllValues().stream()
                .filter(l -> "a".equals(l.getNodeId())).findFirst().orElseThrow()
                .getAttemptCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("RATE_LIMIT으로 계속 실패하면 maxAttempts만큼 실행되고 최종 FAILED, 재시도 소진 기록")
        void rate_limit_retries_until_max_attempts_then_fails() throws Exception {
            retryProperties.setAiMaxAttempts(3);
            RetryScriptExecutor ai = new RetryScriptExecutor(
                Map.of(), Map.of("a", FailureKind.RATE_LIMIT));
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI")),
                List.of(edge("t", "a", null))
            );
            retryRuntime(new RecordingExecutor(NodeType.TRIGGER, log, failNodeIds, conditionResults), ai)
                .execute(mock(WorkflowVersion.class), executionId, new HashMap<>());

            assertThat(ai.callCount("a")).isEqualTo(3);
            verify(execution).fail(true);
            verify(execution, never()).complete();

            ArgumentCaptor<com.ieum.workflowcore.domain.WorkflowExecutionLog> captor =
                ArgumentCaptor.forClass(com.ieum.workflowcore.domain.WorkflowExecutionLog.class);
            verify(logRepository, times(2)).save(captor.capture());
            assertThat(captor.getAllValues().stream()
                .filter(l -> "a".equals(l.getNodeId())).findFirst().orElseThrow()
                .getAttemptCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("durationMs는 백오프 대기를 포함한 전체 시도 합산이다")
        void durationMs_includes_backoff_wait() throws Exception {
            retryProperties.setBackoffMs(50);
            retryProperties.setMaxBackoffMs(1000);
            retryProperties.setJitter(false);
            RetryScriptExecutor ai = new RetryScriptExecutor(
                Map.of("a", 2), Map.of("a", FailureKind.RATE_LIMIT));
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI")),
                List.of(edge("t", "a", null))
            );
            retryRuntime(new RecordingExecutor(NodeType.TRIGGER, log, failNodeIds, conditionResults), ai)
                .execute(mock(WorkflowVersion.class), executionId, new HashMap<>());

            ArgumentCaptor<com.ieum.workflowcore.domain.WorkflowExecutionLog> captor =
                ArgumentCaptor.forClass(com.ieum.workflowcore.domain.WorkflowExecutionLog.class);
            verify(logRepository, times(2)).save(captor.capture());
            long aDurationMs = captor.getAllValues().stream()
                .filter(l -> "a".equals(l.getNodeId())).findFirst().orElseThrow().getDurationMs();
            assertThat(aDurationMs).isGreaterThanOrEqualTo(50L);
        }
    }
}
