package com.ieum.workflowcore.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
import com.ieum.workflowcore.domain.WorkflowExecutionLog;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.engine.event.ExecutionEvent;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.engine.event.ExecutionEventType;
import com.ieum.workflowcore.engine.event.NodeEventStatus;
import com.ieum.workflowcore.engine.executor.AlertNotifier;
import com.ieum.workflowcore.engine.executor.IdempotencyStore;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private IdempotencyStore idempotencyStore;
    private AlertNotifier alertNotifier;
    private WorkflowExecution execution;
    private Workflow workflow;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID executionId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

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
        idempotencyStore = mock(IdempotencyStore.class);
        alertNotifier = mock(AlertNotifier.class);
        execution = mock(WorkflowExecution.class);
        workflow = mock(Workflow.class);
        when(workflow.getUserId()).thenReturn(UUID.randomUUID());
        when(workflow.getId()).thenReturn(workflowId);
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
            new RetryProperties(), idempotencyStore, alertNotifier);
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

    /** retry 설정(예: idempotency 모드)이 포함된 노드 정의. */
    private Map<String, Object> nodeWithRetry(String id, String type, Map<String, Object> retryConfig) {
        Map<String, Object> n = node(id, type);
        Map<String, Object> config = new HashMap<>();
        config.put("retry", retryConfig);
        n.put("config", config);
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
    @DisplayName("노드 실패로 FAILED 확정되면 실패 노드·오류 요약을 담은 알림이 발신된다")
    void failure_sendsAlert() throws Exception {
        UUID workflowId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(workflow.getId()).thenReturn(workflowId);
        when(workflow.getName()).thenReturn("주문 처리");
        when(workflow.getUserId()).thenReturn(ownerId);
        when(execution.getId()).thenReturn(executionId);
        failNodeIds.add("a");
        stubDefinition(
            List.of(node("t", "TRIGGER"), node("a", "AI")),
            List.of(edge("t", "a", null))
        );

        run();

        ArgumentCaptor<AlertNotifier.ExecutionFailureAlert> captor =
            ArgumentCaptor.forClass(AlertNotifier.ExecutionFailureAlert.class);
        verify(alertNotifier).notifyExecutionFailed(captor.capture());
        AlertNotifier.ExecutionFailureAlert alert = captor.getValue();
        assertThat(alert.executionId()).isEqualTo(executionId);
        assertThat(alert.workflowId()).isEqualTo(workflowId);
        assertThat(alert.workflowName()).isEqualTo("주문 처리");
        assertThat(alert.ownerUserId()).isEqualTo(ownerId);
        assertThat(alert.failedNodeId()).isEqualTo("a");
        assertThat(alert.errorSummary()).contains("강제 실패: a");
        assertThat(alert.retryExhausted()).isFalse();
    }

    @Test
    @DisplayName("성공한 실행은 알림을 발신하지 않는다")
    void success_sendsNoAlert() throws Exception {
        stubDefinition(
            List.of(node("t", "TRIGGER"), node("a", "AI")),
            List.of(edge("t", "a", null))
        );

        run();

        verify(alertNotifier, never()).notifyExecutionFailed(any());
    }

    @Test
    @DisplayName("알림 발신이 예외를 던져도 FAILED 상태 처리는 정상 완료된다")
    void alertFailure_doesNotBreakExecution() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("discord down"))
            .when(alertNotifier).notifyExecutionFailed(any());
        failNodeIds.add("a");
        stubDefinition(
            List.of(node("t", "TRIGGER"), node("a", "AI")),
            List.of(edge("t", "a", null))
        );

        run();

        verify(execution).fail(false);
        verify(executionRepository, atLeastOnce()).save(execution);
        verify(eventPublisher).publish(eq(executionId),
            org.mockito.ArgumentMatchers.argThat(
                event -> event.type() == ExecutionEventType.EXECUTION_COMPLETED));
        verify(eventPublisher).complete(executionId);
    }

    @Test
    @DisplayName("정의 로드 실패(실패 노드 특정 불가)도 알림을 발신한다 — failedNodeId는 null")
    void definitionLoadFailure_sendsAlertWithoutNodeId() {
        when(execution.getStatus()).thenReturn(ExecutionStatus.PENDING);
        when(crudService.loadDefinition(any())).thenThrow(new IllegalStateException("정의 없음"));

        assertThatThrownBy(this::run).isInstanceOf(Exception.class);

        ArgumentCaptor<AlertNotifier.ExecutionFailureAlert> captor =
            ArgumentCaptor.forClass(AlertNotifier.ExecutionFailureAlert.class);
        verify(alertNotifier).notifyExecutionFailed(captor.capture());
        assertThat(captor.getValue().failedNodeId()).isNull();
        assertThat(captor.getValue().errorSummary()).isEqualTo("정의 없음");
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
    @DisplayName("SSE 이벤트 공통 필드")
    class SseEventEnvelope {

        private List<ExecutionEvent> publishedEvents() {
            ArgumentCaptor<ExecutionEvent> captor = ArgumentCaptor.forClass(ExecutionEvent.class);
            verify(eventPublisher, atLeastOnce()).publish(eq(executionId), captor.capture());
            return captor.getAllValues();
        }

        private void assertEnvelope(List<ExecutionEvent> events) {
            assertThat(events).isNotEmpty();
            assertThat(events).allSatisfy(event -> {
                assertThat(event.executionId()).isEqualTo(executionId);
                assertThat(event.workflowId()).isEqualTo(workflowId);
                assertThat(event.occurredAt()).isNotNull();
            });
        }

        @Test
        @DisplayName("성공 실행이 발행하는 모든 이벤트에 executionId·workflowId·occurredAt이 실린다")
        void success_events_carry_ids_and_timestamp() throws Exception {
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI")),
                List.of(edge("t", "a", null))
            );

            run();

            List<ExecutionEvent> events = publishedEvents();
            assertEnvelope(events);
            // 시작 2 + 완료 2 + 실행 종료 1
            assertThat(events).hasSize(5);
            assertThat(events.get(events.size() - 1).type())
                .isEqualTo(ExecutionEventType.EXECUTION_COMPLETED);
        }

        @Test
        @DisplayName("실패 실행이 발행하는 모든 이벤트에도 executionId·workflowId·occurredAt이 실린다")
        void failure_events_carry_ids_and_timestamp() throws Exception {
            failNodeIds.add("a");
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI")),
                List.of(edge("t", "a", null))
            );

            run();

            List<ExecutionEvent> events = publishedEvents();
            assertEnvelope(events);
            assertThat(events).anySatisfy(event ->
                assertThat(event.type()).isEqualTo(ExecutionEventType.NODE_FAILED));
            assertThat(events.get(events.size() - 1).executionStatus())
                .isEqualTo(ExecutionStatus.FAILED);
        }

        @Test
        @DisplayName("재처리 스킵 노드의 NODE_STARTED에도 공통 필드가 실린다")
        void preCompleted_skip_events_carry_ids_and_timestamp() throws Exception {
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI")),
                List.of(edge("t", "a", null))
            );

            runtime().execute(mock(WorkflowVersion.class), executionId, new HashMap<>(),
                Map.of("a", Map.of("output", "원 실행 값")));

            assertEnvelope(publishedEvents());
        }

        @Test
        @DisplayName("NODE_STARTED는 status RUNNING을 싣는다")
        void nodeStarted_carries_running_status() throws Exception {
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI")),
                List.of(edge("t", "a", null))
            );

            run();

            List<ExecutionEvent> started = publishedEvents().stream()
                .filter(e -> e.type() == ExecutionEventType.NODE_STARTED)
                .toList();
            assertThat(started).hasSize(2);
            assertThat(started).allSatisfy(event ->
                assertThat(event.status()).isEqualTo(NodeEventStatus.RUNNING));
        }
    }

    @Nested
    @DisplayName("재처리 — 원 실행 성공 노드 스킵")
    class PreCompletedOutputs {

        private void runWith(Map<String, Map<String, Object>> preCompleted) throws Exception {
            runtime().execute(mock(WorkflowVersion.class), executionId, new HashMap<>(), preCompleted);
        }

        private List<WorkflowExecutionLog> capturedLogs(int expectedSaves) {
            ArgumentCaptor<WorkflowExecutionLog> captor =
                ArgumentCaptor.forClass(WorkflowExecutionLog.class);
            verify(logRepository, times(expectedSaves)).save(captor.capture());
            return captor.getAllValues();
        }

        private WorkflowExecutionLog logOf(List<WorkflowExecutionLog> logs, String nodeId) {
            return logs.stream().filter(l -> nodeId.equals(l.getNodeId())).findFirst().orElseThrow();
        }

        @Test
        @DisplayName("주입된 노드는 executor 호출 없이 SKIPPED로 기록되고, 나머지는 실행된다")
        void preCompleted_node_is_skipped_and_logged() throws Exception {
            // t -> a -> b, a는 원 실행에서 성공 → 스킵
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI"), node("b", "AI")),
                List.of(edge("t", "a", null), edge("a", "b", null))
            );

            runWith(Map.of("a", Map.of("output", "원본-a-출력")));

            assertThat(log).containsExactlyInAnyOrder("t", "b");
            assertThat(log).doesNotContain("a");

            List<WorkflowExecutionLog> logs = capturedLogs(3);
            assertThat(logOf(logs, "a").getStatus()).isEqualTo(ExecutionLogStatus.SKIPPED);
            assertThat(logOf(logs, "a").getOutputJson()).contains("원본-a-출력");
            assertThat(logOf(logs, "t").getStatus()).isEqualTo(ExecutionLogStatus.SUCCESS);
            assertThat(logOf(logs, "b").getStatus()).isEqualTo(ExecutionLogStatus.SUCCESS);
            verify(execution).complete();
        }

        @Test
        @DisplayName("스킵된 노드의 출력은 컨텍스트에 들어가 뒤 노드의 변수 치환에 쓰인다")
        void skipped_node_output_feeds_downstream_variables() throws Exception {
            Map<String, Object> b = node("b", "AI");
            b.put("config", new HashMap<>(Map.of("prompt", "{{nodes.a.output.text}}")));
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI"), b),
                List.of(edge("t", "a", null), edge("a", "b", null))
            );

            runWith(Map.of("a", Map.of("text", "복원된값")));

            assertThat(logOf(capturedLogs(3), "b").getInputJson()).contains("복원된값");
        }

        @Test
        @DisplayName("스킵 노드가 fan-out 부모여도 자식 분기가 모두 이어서 실행된다")
        void skipped_node_still_propagates_edges() throws Exception {
            // t -> a, a -> b, a -> c
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI"), node("b", "AI"), node("c", "AI")),
                List.of(edge("t", "a", null), edge("a", "b", null), edge("a", "c", null))
            );

            runWith(Map.of("a", Map.of("output", "x")));

            assertThat(log).containsExactlyInAnyOrder("t", "b", "c");
            verify(execution).complete();
        }

        @Test
        @DisplayName("스킵된 CONDITION 노드는 복원된 result로 원 실행과 같은 분기를 탄다")
        void skipped_condition_restores_branch_direction() throws Exception {
            // 원 실행에서 cond가 false로 평가됐다면 재처리도 false 분기여야 한다.
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("cond", "CONDITION"),
                    node("yes", "AI"), node("no", "AI")),
                List.of(edge("t", "cond", null), edge("cond", "yes", "true"),
                    edge("cond", "no", "false"))
            );

            runWith(Map.of("cond", Map.of("result", false)));

            assertThat(log).containsExactlyInAnyOrder("t", "no");
            assertThat(log).doesNotContain("cond", "yes");
            verify(execution).complete();
        }

        @Test
        @DisplayName("TRIGGER 노드는 주입돼 있어도 스킵하지 않고 다시 실행한다(마스킹된 로그값 대신 복호본)")
        void trigger_node_is_never_skipped() throws Exception {
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI")),
                List.of(edge("t", "a", null))
            );

            // 원 실행 로그에는 마스킹된 트리거 output이 남아 있다.
            runWith(Map.of("t", Map.of("apiKey", "***"), "a", Map.of("output", "x")));

            assertThat(log).contains("t");     // executor가 다시 호출된다
            assertThat(log).doesNotContain("a");
            List<WorkflowExecutionLog> logs = capturedLogs(2);
            assertThat(logOf(logs, "t").getStatus()).isEqualTo(ExecutionLogStatus.SUCCESS);
            assertThat(logOf(logs, "t").getOutputJson()).doesNotContain("***");
            assertThat(logOf(logs, "a").getStatus()).isEqualTo(ExecutionLogStatus.SKIPPED);
        }

        @Test
        @DisplayName("회귀: 주입이 비면 모든 노드를 실행하고 SKIPPED 로그가 하나도 없다")
        void empty_preCompleted_behaves_like_normal_execution() throws Exception {
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI"), node("b", "AI")),
                List.of(edge("t", "a", null), edge("a", "b", null))
            );

            runWith(Map.of());

            assertThat(log).containsExactlyInAnyOrder("t", "a", "b");
            assertThat(capturedLogs(3))
                .noneMatch(l -> l.getStatus() == ExecutionLogStatus.SKIPPED);
            verify(execution).complete();
        }

        @Test
        @DisplayName("회귀: 3-인자 execute는 주입 없는 실행과 동일하다")
        void three_arg_execute_runs_every_node() throws Exception {
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI")),
                List.of(edge("t", "a", null))
            );

            run();

            assertThat(log).containsExactlyInAnyOrder("t", "a");
            assertThat(capturedLogs(2))
                .allMatch(l -> l.getStatus() == ExecutionLogStatus.SUCCESS);
        }

        @Test
        @DisplayName("주입된 노드가 실패하던 노드 뒤에 있어도 실패 노드는 다시 실행된다(at-least-once)")
        void non_injected_failing_node_runs_again() throws Exception {
            failNodeIds.add("b");
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI"), node("b", "AI")),
                List.of(edge("t", "a", null), edge("a", "b", null))
            );

            runWith(Map.of("a", Map.of("output", "x")));

            assertThat(log).contains("b");
            assertThat(log).doesNotContain("a");
            verify(execution).fail(false);
        }
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

    /**
     * 4-인자 execute로 전달받은 {@link NodeExecutor.NodeAttempt}를 그대로 기록하는 fake executor.
     * 재시도 회차마다 같은 멱등성 키가 전달되는지(C-1 관련 계약) 검증하는 데 쓴다.
     */
    static class AttemptCapturingExecutor implements NodeExecutor {
        private final int succeedOnAttempt;
        final List<NodeExecutor.NodeAttempt> capturedAttempts = new CopyOnWriteArrayList<>();

        AttemptCapturingExecutor(int succeedOnAttempt) {
            this.succeedOnAttempt = succeedOnAttempt;
        }

        @Override
        public NodeType getNodeType() {
            return NodeType.AI;
        }

        @Override
        public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
            throw new UnsupportedOperationException("이 fake executor는 4-인자 execute만 지원");
        }

        @Override
        public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor,
                                      NodeExecutor.NodeAttempt attempt) {
            capturedAttempts.add(attempt);
            if (attempt.attempt() < succeedOnAttempt) {
                return ExecutorResult.failure("스크립트 실패", 1, FailureKind.RATE_LIMIT);
            }
            return ExecutorResult.success(Map.of("output", node.getId()), 1);
        }
    }

    /**
     * Http/AgentNodeExecutor처럼 호출 직전 {@code IdempotencyStore.markInFlight}를 실제로 소비하는
     * fake executor. 마커가 통과하면(true) 항상 재시도 대상 원인(TIMEOUT)으로 실패한다 —
     * SyncExecutionRuntime이 MARKER 노드의 2회차를 시작하지 않는지(I-1) 검증하는 데 쓴다.
     */
    static class MarkerAwareExecutor implements NodeExecutor {
        private final IdempotencyStore store;
        final AtomicInteger calls = new AtomicInteger();
        private volatile NodeExecutor.NodeAttempt lastAttempt;

        MarkerAwareExecutor(IdempotencyStore store) {
            this.store = store;
        }

        @Override
        public NodeType getNodeType() {
            return NodeType.AI;
        }

        @Override
        public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
            throw new UnsupportedOperationException("이 fake executor는 4-인자 execute만 지원");
        }

        @Override
        public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor,
                                      NodeExecutor.NodeAttempt attempt) {
            calls.incrementAndGet();
            lastAttempt = attempt;
            if (attempt.policy().idempotency().usesMarker()
                    && !store.markInFlight(attempt.idempotencyKey(), NodeExecutor.markerTtl(attempt.policy()))) {
                return ExecutorResult.failure("중복 호출 차단", 1, FailureKind.CLIENT_ERROR);
            }
            return ExecutorResult.failure("원 실패: 타임아웃", 1, FailureKind.TIMEOUT);
        }

        NodeExecutor.NodeAttempt lastAttempt() {
            return lastAttempt;
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
                List.of(executors), retryProperties, idempotencyStore, alertNotifier);
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

        @Test
        @DisplayName("재시도 두 회차가 같은 멱등성 키를 전달받는다")
        void retriedAttempts_receiveSameIdempotencyKey() throws Exception {
            // HEADER 모드 사용 — MARKER는 리뷰 I-1 수정으로 attempt 1 이후 재시도를 하지 않으므로
            // "여러 attempt에 걸쳐 같은 키가 전달되는지"는 실제로 재시도가 일어나는 모드로 검증해야 한다.
            AttemptCapturingExecutor ai = new AttemptCapturingExecutor(2);
            stubDefinition(
                List.of(node("t", "TRIGGER"),
                    nodeWithRetry("a", "AI", Map.of("idempotency", "HEADER", "maxAttempts", 2))),
                List.of(edge("t", "a", null))
            );
            retryRuntime(new RecordingExecutor(NodeType.TRIGGER, log, failNodeIds, conditionResults), ai)
                .execute(mock(WorkflowVersion.class), executionId, new HashMap<>());

            assertThat(ai.capturedAttempts).hasSize(2);
            assertThat(ai.capturedAttempts.get(0).idempotencyKey()).isNotBlank();
            assertThat(ai.capturedAttempts.get(0).idempotencyKey())
                .isEqualTo(ai.capturedAttempts.get(1).idempotencyKey());
        }

        @Test
        @DisplayName("MARKER 모드: attempt 1이 재시도 대상 원인(RATE_LIMIT)으로 실패해도 2회차 없이 원 실패가 보존되고 " +
            "retryExhausted=false다(I-1), clearInFlight는 1회 호출된다(C-1)")
        void markerMode_retryableFailure_doesNotEnterSecondAttempt() throws Exception {
            when(idempotencyStore.markInFlight(any(), any())).thenReturn(true);
            // Http/AgentNodeExecutor처럼 markInFlight를 실제로 소비하는 fake — 러ntime의
            // 조기 break가 executor 레벨 마커 상호작용과 함께 동작하는지까지 검증한다.
            MarkerAwareExecutor ai = new MarkerAwareExecutor(idempotencyStore);
            stubDefinition(
                List.of(node("t", "TRIGGER"),
                    nodeWithRetry("a", "AI", Map.of("idempotency", "MARKER", "maxAttempts", 3))),
                List.of(edge("t", "a", null))
            );
            retryRuntime(new RecordingExecutor(NodeType.TRIGGER, log, failNodeIds, conditionResults), ai)
                .execute(mock(WorkflowVersion.class), executionId, new HashMap<>());

            // 2회차가 아예 시작되지 않는다 — maxAttempts=3이지만 1번만 호출됨
            assertThat(ai.calls.get()).isEqualTo(1);
            verify(idempotencyStore, times(1)).markInFlight(any(), any());
            String key = ai.lastAttempt().idempotencyKey();
            verify(idempotencyStore, times(1)).clearInFlight(key);
            // 소진(retryExhausted)이 아니라 attempt=1의 원인 그대로 FAILED다
            verify(execution).fail(false);
            verify(execution, never()).complete();

            ArgumentCaptor<com.ieum.workflowcore.domain.WorkflowExecutionLog> captor =
                ArgumentCaptor.forClass(com.ieum.workflowcore.domain.WorkflowExecutionLog.class);
            verify(logRepository, times(2)).save(captor.capture());
            com.ieum.workflowcore.domain.WorkflowExecutionLog aLog = captor.getAllValues().stream()
                .filter(l -> "a".equals(l.getNodeId())).findFirst().orElseThrow();
            // 원 실패(타임아웃)가 그대로 남아야 한다 — executor의 "중복 호출 차단" 문구로 덮이면 안 된다.
            assertThat(aLog.getErrorMessage()).contains("원 실패: 타임아웃");
            assertThat(aLog.getErrorMessage()).doesNotContain("중복 호출 차단");
            assertThat(aLog.getAttemptCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("NONE 모드(기본값)에서는 clearInFlight가 호출되지 않는다")
        void noneMode_neverCallsClearInFlight() throws Exception {
            RetryScriptExecutor ai = new RetryScriptExecutor(
                Map.of("a", 2), Map.of("a", FailureKind.RATE_LIMIT));
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI")),
                List.of(edge("t", "a", null))
            );
            retryRuntime(new RecordingExecutor(NodeType.TRIGGER, log, failNodeIds, conditionResults), ai)
                .execute(mock(WorkflowVersion.class), executionId, new HashMap<>());

            verify(idempotencyStore, never()).clearInFlight(any());
        }

        @Test
        @DisplayName("backoff 대기 중 인터럽트로 중단되면(attempt<maxAttempts) 소진으로 오판정하지 않는다")
        void interrupted_during_backoff_is_not_retry_exhausted() throws Exception {
            retryProperties.setAiMaxAttempts(3);
            AtomicInteger calls = new AtomicInteger();
            // 2회차 시도에서 스스로를 인터럽트한다 — Thread.sleep은 지속시간과 무관하게 인터럽트
            // 플래그가 서 있으면 즉시 InterruptedException을 던지므로, 3회차(maxAttempts)까지
            // 가지 못하고 attempt=2에서 중단되는 상황을 타이밍 없이 재현할 수 있다.
            NodeExecutor ai = new NodeExecutor() {
                @Override
                public NodeType getNodeType() {
                    return NodeType.AI;
                }

                @Override
                public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
                    if (calls.incrementAndGet() == 2) {
                        Thread.currentThread().interrupt();
                    }
                    return ExecutorResult.failure("스크립트 실패", 1, FailureKind.RATE_LIMIT);
                }
            };
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI")),
                List.of(edge("t", "a", null))
            );
            retryRuntime(new RecordingExecutor(NodeType.TRIGGER, log, failNodeIds, conditionResults), ai)
                .execute(mock(WorkflowVersion.class), executionId, new HashMap<>());

            // maxAttempts=3까지 못 가고 2회차 인터럽트로 중단됨(attempt<maxAttempts)
            assertThat(calls.get()).isEqualTo(2);
            verify(execution).fail(false);
        }

        @Test
        @DisplayName("fan-out 병렬: 한 갈래가 재시도하는 동안 형제 갈래가 계속 진행된다")
        void sibling_branch_progresses_while_other_retries() throws Exception {
            retryProperties.setAiMaxAttempts(3);
            CountDownLatch dCompleted = new CountDownLatch(1);
            List<String> completionOrder = new CopyOnWriteArrayList<>();
            AtomicInteger aAttempts = new AtomicInteger();
            AtomicBoolean dFinishedBeforeARetried = new AtomicBoolean(false);

            // t -> a(재시도) / t -> d(독립 형제 브랜치). 같은 NodeType.AI는 executorMap에
            // 하나만 등록되므로(같은 타입 마지막 등록이 덮어씀) 한 executor가 nodeId로 분기한다.
            NodeExecutor combined = new NodeExecutor() {
                @Override
                public NodeType getNodeType() {
                    return NodeType.AI;
                }

                @Override
                public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
                    if ("a".equals(node.getId())) {
                        if (aAttempts.incrementAndGet() == 1) {
                            return ExecutorResult.failure("1차 실패", 1, FailureKind.RATE_LIMIT);
                        }
                        // 재시도(2회차) 진행 중 — d가 이미 끝났어야 병렬 진행이 증명된다.
                        // 직렬화되는 회귀가 생기면 d가 끝나지 못해 여기서 타임아웃된다.
                        try {
                            dFinishedBeforeARetried.set(dCompleted.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        completionOrder.add("a");
                        return ExecutorResult.success(Map.of("output", "a-done"), 1);
                    }
                    completionOrder.add("d");
                    dCompleted.countDown();
                    return ExecutorResult.success(Map.of("output", "d-done"), 1);
                }
            };

            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI"), node("d", "AI")),
                List.of(edge("t", "a", null), edge("t", "d", null))
            );
            retryRuntime(new RecordingExecutor(NodeType.TRIGGER, log, failNodeIds, conditionResults), combined)
                .execute(mock(WorkflowVersion.class), executionId, new HashMap<>());

            assertThat(aAttempts.get()).isEqualTo(2);
            assertThat(dFinishedBeforeARetried.get())
                .as("a가 재시도하는 동안 형제 브랜치 d가 완료될 기회를 얻어야 한다")
                .isTrue();
            assertThat(completionOrder).containsExactly("d", "a");
            verify(execution).complete();
        }

        @Test
        @DisplayName("재시도로 성공한 노드의 output이 후속 노드 입력에 변수 치환으로 전파된다")
        void retried_node_output_propagates_to_downstream_input() throws Exception {
            Map<String, Map<String, Object>> capturedInputs = new ConcurrentHashMap<>();
            AtomicInteger aAttempts = new AtomicInteger();

            NodeExecutor combined = new NodeExecutor() {
                @Override
                public NodeType getNodeType() {
                    return NodeType.AI;
                }

                @Override
                public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
                    capturedInputs.put(node.getId(), input);
                    if ("a".equals(node.getId())) {
                        if (aAttempts.incrementAndGet() == 1) {
                            return ExecutorResult.failure("1차 실패", 1, FailureKind.RATE_LIMIT);
                        }
                        return ExecutorResult.success(Map.of("output", "retried-value"), 1);
                    }
                    return ExecutorResult.success(Map.of("output", node.getId()), 1);
                }
            };

            Map<String, Object> bNode = node("b", "AI");
            bNode.put("config", Map.of("value", "{{nodes.a.output.output}}"));
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI"), bNode),
                List.of(edge("t", "a", null), edge("a", "b", null))
            );
            retryRuntime(new RecordingExecutor(NodeType.TRIGGER, log, failNodeIds, conditionResults), combined)
                .execute(mock(WorkflowVersion.class), executionId, new HashMap<>());

            assertThat(aAttempts.get()).isEqualTo(2);
            assertThat(capturedInputs.get("b")).containsEntry("value", "retried-value");
            verify(execution).complete();
        }

        @Test
        @DisplayName("재시도 소진 실패 시 nodeFailed·executionCompleted(FAILED) SSE 이벤트가 기존과 동일하게 발행된다")
        void retry_exhausted_failure_publishes_same_sse_events() throws Exception {
            retryProperties.setAiMaxAttempts(3);
            RetryScriptExecutor ai = new RetryScriptExecutor(Map.of(), Map.of("a", FailureKind.RATE_LIMIT));
            stubDefinition(
                List.of(node("t", "TRIGGER"), node("a", "AI")),
                List.of(edge("t", "a", null))
            );
            retryRuntime(new RecordingExecutor(NodeType.TRIGGER, log, failNodeIds, conditionResults), ai)
                .execute(mock(WorkflowVersion.class), executionId, new HashMap<>());

            ArgumentCaptor<ExecutionEvent> captor = ArgumentCaptor.forClass(ExecutionEvent.class);
            verify(eventPublisher, atLeastOnce()).publish(eq(executionId), captor.capture());
            List<ExecutionEvent> events = captor.getAllValues();

            // 재시도 소진(maxAttempts=3)까지 갔어도 nodeFailed는 노드당 정확히 1회만 —
            // 시도마다 발행되지 않고 최종 실패 시 한 번만 나가는 기존 동작과 동일해야 한다.
            List<ExecutionEvent> nodeFailedEvents = events.stream()
                .filter(e -> e.type() == ExecutionEventType.NODE_FAILED)
                .toList();
            assertThat(nodeFailedEvents).hasSize(1);
            assertThat(nodeFailedEvents.get(0).nodeId()).isEqualTo("a");
            assertThat(nodeFailedEvents.get(0).errorMessage()).contains("스크립트 실패");

            ExecutionEvent lastEvent = events.get(events.size() - 1);
            assertThat(lastEvent.type()).isEqualTo(ExecutionEventType.EXECUTION_COMPLETED);
            assertThat(lastEvent.executionStatus()).isEqualTo(ExecutionStatus.FAILED);
        }
    }
}
