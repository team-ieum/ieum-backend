package com.ieum.api.workflow.queue;

import static com.ieum.api.workflow.queue.ExecutionJobQueue.GROUP;
import static com.ieum.api.workflow.queue.ExecutionJobQueue.STREAM_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.service.ExecutionRetryService;
import com.ieum.common.util.AesEncryptor;
import com.ieum.workflowcore.config.RetryProperties;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowExecutionLog;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.engine.SyncExecutionRuntime;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.engine.executor.AlertNotifier;
import com.ieum.workflowcore.engine.executor.IdempotencyStore;
import com.ieum.workflowcore.engine.executor.NodeExecutor;
import com.ieum.workflowcore.engine.executor.TriggerNodeExecutor;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.repository.WorkflowQueryRepository;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 재처리 → 큐 → 워커 → 런타임을 실물로 이어 붙인 통합 테스트.
 *
 * <p>단위 테스트들은 이 경로의 각 구간을 이웃을 목으로 세운 채 검증한다
 * ({@code ExecutionRetryServiceTest}는 목 러너에서, {@code ExecutionJobWorkerTest}는 목 큐에서,
 * {@code SyncExecutionRuntimeTest}는 손으로 넣은 스킵 목록에서 끝난다). 여기서는 그 사이의 이음매를
 * 검증한다 — 재처리가 만든 잡이 실제로 큐를 통과해 워커에서 실행되고, 그 과정에서
 * <b>암호문으로 저장된 트리거 입력</b>과 <b>원 실행의 node_runs에서 읽은 스킵 대상</b>이
 * DB만을 통해 복원되는지.
 *
 * <p><b>실물</b>: {@link ExecutionRetryService}, {@link WorkflowExecutionRunner},
 * {@link ExecutionJobQueue}, {@link ExecutionJobWorker}, {@link SyncExecutionRuntime},
 * {@link WorkflowExecutionService}, {@link AesEncryptor}, {@link TriggerNodeExecutor}.
 * <b>목</b>: Redis(인메모리 스트림), JPA 리포지터리(인메모리 맵), 정의 로드, SSE 퍼블리셔,
 * AI 노드 executor.
 *
 * <p>실 Redis·실 PG는 띄우지 않는다. 이 스위트로 검증 불가한 두 영역(TOCTOU 동시성, 실 Redis에
 * 대한 그룹 생성·회수 시퀀스)은 태스크 리포트에 수동 확인 항목으로 남겼다.
 */
class ExecutionRetryQueueIntegrationTest {

    /** 원 실행의 트리거 입력. {@code token}은 마스킹 대상 키라 node_runs에는 {@code ***}로 남는다. */
    private static final Map<String, Object> TRIGGER_DATA =
        Map.of("score", "85", "token", "sk-원본토큰");

    private static final String ORIGINAL_A_OUTPUT = "{\"text\":\"원본-a-출력\"}";

    /**
     * 원 실행이 node_runs에 남긴 트리거 output. 저장 시 {@code SensitiveDataMasker}를 거쳐
     * {@code token}이 {@code ***}로 뭉개져 있다 — 재처리가 이 값을 재사용하면 후속 노드로
     * 마스킹된 자격증명이 흘러간다. 트리거를 스킵 대상에서 제외하는 이유가 이것이다.
     */
    private static final String ORIGINAL_MASKED_TRIGGER_OUTPUT =
        "{\"triggerType\":\"WEBHOOK\",\"score\":\"85\",\"token\":\"***\"}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();

    /** 인메모리 workflow_runs / node_runs. */
    private final Map<UUID, WorkflowExecution> rows = new LinkedHashMap<>();
    private final List<WorkflowExecutionLog> nodeRuns = new ArrayList<>();

    /** 인메모리 Redis Stream. enqueue가 넣고 테스트가 꺼내 워커에 먹인다. */
    private final Deque<MapRecord<String, String, String>> stream = new ArrayDeque<>();
    private int nextRecordId = 1;

    /** AI 노드 executor가 실제로 호출된 노드와 그때 받은 입력. 런타임 워커 스레드가 쓴다. */
    private final ConcurrentLinkedQueue<String> aiCalls = new ConcurrentLinkedQueue<>();
    private final Map<String, Map<String, Object>> aiInputs = new ConcurrentHashMap<>();

    private WorkflowExecutionRepository executionRepository;
    private WorkflowExecutionLogRepository logRepository;
    private WorkflowCrudService crudService;
    private StreamOperations<String, String, String> streamOperations;
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    private WorkflowExecutionService workflowExecutionService;
    private ExecutionJobWorker worker;
    private ExecutionRetryService retryService;

    private Workflow workflow;
    private WorkflowVersion version;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        AesEncryptor aesEncryptor = new AesEncryptor();
        ReflectionTestUtils.setField(aesEncryptor, "secretKey", "0123456789abcdef0123456789abcdef");
        aesEncryptor.validateKey();

        executionRepository = fakeExecutionRepository();
        logRepository = fakeLogRepository();
        crudService = Mockito.mock(WorkflowCrudService.class);

        workflowExecutionService = new WorkflowExecutionService(
            executionRepository, logRepository,
            Mockito.mock(WorkflowQueryRepository.class), objectMapper, aesEncryptor,
            Mockito.mock(AlertNotifier.class));

        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        streamOperations = Mockito.mock(StreamOperations.class);
        container = Mockito.mock(StreamMessageListenerContainer.class);
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);
        when(container.isRunning()).thenReturn(true);
        when(streamOperations.add(any(MapRecord.class), any(XAddOptions.class)))
            .thenAnswer(invocation -> {
                MapRecord<String, String, String> published = invocation.getArgument(0);
                RecordId id = RecordId.of(nextRecordId++ + "-0");
                stream.add(published.withId(id));
                return id;
            });

        ExecutionJobQueue queue = new ExecutionJobQueue(redisTemplate, container);
        queue.markConsumerHealthy();

        WorkflowExecutionRunner runner = new WorkflowExecutionRunner(
            runtime(), workflowExecutionService, queue);
        // 워커 실행 풀은 호출 스레드 직접 실행 — 큐 소비를 결정론적으로 관찰하기 위함.
        worker = new ExecutionJobWorker(redisTemplate, executionRepository,
            workflowExecutionService, runner, Runnable::run);
        retryService = new ExecutionRetryService(crudService, workflowExecutionService, runner);

        workflow = Workflow.builder().userId(userId).name("통합").isActive(true).build();
        ReflectionTestUtils.setField(workflow, "id", UUID.randomUUID());
        version = Mockito.mock(WorkflowVersion.class);
        when(version.getId()).thenReturn(UUID.randomUUID());
        when(crudService.getWorkflowByOwner(any(), any())).thenReturn(workflow);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 인메모리 영속 계층 — 실 PG 없이 재처리 링크 역방향 조회까지 재현한다
    // ──────────────────────────────────────────────────────────────────────

    private WorkflowExecutionRepository fakeExecutionRepository() {
        WorkflowExecutionRepository repository = Mockito.mock(WorkflowExecutionRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> {
            WorkflowExecution execution = invocation.getArgument(0);
            if (execution.getId() == null) {
                // @GeneratedValue(UUID) 대역 — 저장 시점에 PK가 부여되는 동작을 재현한다.
                ReflectionTestUtils.setField(execution, "id", UUID.randomUUID());
            }
            rows.put(execution.getId(), execution);
            return execution;
        });
        when(repository.findById(any())).thenAnswer(this::findRow);
        when(repository.findWithVersionById(any())).thenAnswer(this::findRow);
        when(repository.findWithWorkflowById(any())).thenAnswer(this::findRow);
        when(repository.findByIdForUpdate(any())).thenAnswer(this::findRow);
        when(repository.findByRetriedByExecutionId(any())).thenAnswer(invocation -> {
            UUID retryExecutionId = invocation.getArgument(0);
            return rows.values().stream()
                .filter(row -> retryExecutionId.equals(row.getRetriedByExecutionId()))
                .findFirst();
        });
        return repository;
    }

    private Optional<WorkflowExecution> findRow(org.mockito.invocation.InvocationOnMock invocation) {
        return Optional.ofNullable(rows.get((UUID) invocation.getArgument(0)));
    }

    private WorkflowExecutionLogRepository fakeLogRepository() {
        WorkflowExecutionLogRepository repository =
            Mockito.mock(WorkflowExecutionLogRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> {
            WorkflowExecutionLog entry = invocation.getArgument(0);
            nodeRuns.add(entry);
            return entry;
        });
        when(repository.findByExecutionIdAndStatusIn(any(), any())).thenAnswer(invocation -> {
            UUID executionId = invocation.getArgument(0);
            Collection<ExecutionLogStatus> statuses = invocation.getArgument(1);
            return nodeRuns.stream()
                .filter(entry -> entry.getExecution() != null
                    && executionId.equals(entry.getExecution().getId()))
                .filter(entry -> statuses.contains(entry.getStatus()))
                .toList();
        });
        return repository;
    }

    // ──────────────────────────────────────────────────────────────────────
    // 실행 런타임 — TRIGGER는 실물, AI만 기록용 대역
    // ──────────────────────────────────────────────────────────────────────

    /**
     * TRIGGER는 실물 {@link TriggerNodeExecutor}를 쓴다 — 복호된 트리거 입력이 노드 입출력까지
     * 흐르는 경로를 프로덕션 코드로 검증해야 하기 때문이다. AI만 호출 여부를 관찰할 대역으로 바꾼다.
     */
    private SyncExecutionRuntime runtime() {
        RetryProperties retryProperties = new RetryProperties();
        retryProperties.setBackoffMs(0);   // 이 스위트는 실패를 만들지 않지만 대기로 느려지지 않게 한다
        SyncExecutionRuntime runtime = new SyncExecutionRuntime(
            objectMapper, logRepository, executionRepository, crudService,
            Mockito.mock(ExecutionEventPublisher.class),
            List.of(new TriggerNodeExecutor(), new RecordingAiExecutor()),
            retryProperties, Mockito.mock(IdempotencyStore.class),
            Mockito.mock(AlertNotifier.class));
        ReflectionTestUtils.invokeMethod(runtime, "initExecutorMap");
        ReflectionTestUtils.setField(runtime, "parallelism", 2);
        return runtime;
    }

    private class RecordingAiExecutor implements NodeExecutor {

        @Override
        public NodeType getNodeType() {
            return NodeType.AI;
        }

        @Override
        public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
            aiCalls.add(node.getId());
            aiInputs.put(node.getId(), input);
            return ExecutorResult.success(Map.of("text", node.getId() + "-재실행"), 1);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 시나리오 준비
    // ──────────────────────────────────────────────────────────────────────

    /**
     * t(TRIGGER) → a(AI) → b(AI). 트리거 노드 config는 자기 seed된 트리거 입력을 참조하고,
     * b는 a의 출력을 참조한다 — 두 변수 경로가 재처리에서도 살아 있는지 보기 위한 정의다.
     */
    private void stubDefinition() {
        Map<String, Object> trigger = node("t", "TRIGGER");
        trigger.put("config", new HashMap<>(Map.of(
            "triggerType", "WEBHOOK",
            "score", "{{nodes.t.output.score}}",
            "token", "{{nodes.t.output.token}}")));
        Map<String, Object> b = node("b", "AI");
        b.put("config", new HashMap<>(Map.of(
            "prompt", "{{nodes.a.output.text}}",
            "auth", "{{nodes.t.output.token}}")));

        WorkflowDefinitionDocument definition = WorkflowDefinitionDocument.builder()
            .nodes(List.of(trigger, node("a", "AI"), b))
            .edges(List.of(edge("t", "a"), edge("a", "b")))
            .build();
        when(crudService.loadDefinition(any())).thenReturn(definition);
    }

    private Map<String, Object> node(String id, String type) {
        Map<String, Object> n = new HashMap<>();
        n.put("id", id);
        n.put("type", type);
        n.put("label", id);
        n.put("config", new HashMap<>());
        return n;
    }

    private Map<String, Object> edge(String source, String target) {
        Map<String, Object> e = new HashMap<>();
        e.put("source", source);
        e.put("target", target);
        e.put("conditionType", null);
        return e;
    }

    /** 트리거 입력을 실제로 암호화해 저장한 FAILED 실행을 만든다. */
    private WorkflowExecution givenFailedOriginal() {
        WorkflowExecution original = workflowExecutionService.prepareExecution(
            workflow, version, TriggerType.WEBHOOK, TRIGGER_DATA);
        original.fail();
        return original;
    }

    /**
     * 원 실행이 남긴 성공 노드 흔적. 재처리는 {@code findByRetriedByExecutionId} 역방향 조회로
     * 이 로그를 읽어 스킵 대상을 정한다 — 트리거 t는 여기 있어도 스킵되지 않아야 한다.
     */
    private void givenOriginalNodeRuns(WorkflowExecution original) {
        nodeRuns.add(originalNodeRun(original, "t", NodeType.TRIGGER,
            ORIGINAL_MASKED_TRIGGER_OUTPUT));
        nodeRuns.add(originalNodeRun(original, "a", NodeType.AI, ORIGINAL_A_OUTPUT));
    }

    private WorkflowExecutionLog originalNodeRun(WorkflowExecution original, String nodeId,
            NodeType nodeType, String outputJson) {
        return WorkflowExecutionLog.builder()
            .execution(original)
            .nodeId(nodeId)
            .nodeType(nodeType)
            .status(ExecutionLogStatus.SUCCESS)
            .outputJson(outputJson)
            .build();
    }

    /** 재처리 API 호출 + 트랜잭션 커밋. 커밋 콜백에서 큐 투입이 일어난다. */
    private UUID retryAndCommit(UUID originalId) {
        TransactionSynchronizationManager.initSynchronization();
        UUID retryId = retryService.retryExecution(userId, originalId).getId();
        for (TransactionSynchronization sync :
                TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        TransactionSynchronizationManager.clearSynchronization();
        return retryId;
    }

    /** 큐에서 잡 하나를 꺼내 워커에 넘긴다. */
    private MapRecord<String, String, String> consumeOneJob() {
        MapRecord<String, String, String> job = stream.poll();
        assertThat(job).as("큐에 잡이 없다").isNotNull();
        worker.onMessage(job);
        return job;
    }

    private WorkflowExecutionLog nodeRunOf(UUID executionId, String nodeId) {
        return nodeRuns.stream()
            .filter(entry -> executionId.equals(entry.getExecution().getId()))
            .filter(entry -> nodeId.equals(entry.getNodeId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "node_runs에 없음 — executionId: " + executionId + ", nodeId: " + nodeId));
    }

    // ──────────────────────────────────────────────────────────────────────
    // 시나리오
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("재처리는 커밋 시점에 큐로만 들어가고, 워커가 잡을 꺼낸 뒤에야 실제로 실행된다")
    void 재처리는_큐를_거쳐_워커에서_실행된다() {
        WorkflowExecution original = givenFailedOriginal();
        givenOriginalNodeRuns(original);
        stubDefinition();

        UUID retryId = retryAndCommit(original.getId());

        // 커밋 직후: 잡은 큐에 있고 실행은 아직 시작조차 하지 않았다.
        // 페이로드는 executionId 하나뿐이라 스킵 목록·트리거 입력이 Redis로 새지 않는다.
        assertThat(stream).hasSize(1);
        assertThat(stream.peek().getValue())
            .containsExactly(entry(ExecutionJobQueue.FIELD_EXECUTION_ID, retryId.toString()));
        assertThat(rows.get(retryId).getStatus()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(aiCalls).isEmpty();
        assertThat(original.getRetriedByExecutionId()).isEqualTo(retryId);

        MapRecord<String, String, String> job = consumeOneJob();

        // 워커가 꺼낸 뒤에 비로소 실행이 끝까지 돈다 — 스킵 대상은 DB에서 복원됐다.
        assertThat(rows.get(retryId).getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(aiCalls).containsExactly("b");
        assertThat(nodeRunOf(retryId, "a").getStatus()).isEqualTo(ExecutionLogStatus.SKIPPED);
        verify(streamOperations).acknowledge(STREAM_KEY, GROUP, job.getId());
    }

    @Test
    @DisplayName("재처리의 trigger_data는 새 암호문이지만 복호하면 원 실행과 같고, 그 평문이 트리거 노드까지 흐른다")
    void 재처리_트리거입력은_복호하면_원본과_같다() {
        WorkflowExecution original = givenFailedOriginal();
        String originalCipherText = original.getTriggerData();
        givenOriginalNodeRuns(original);
        stubDefinition();

        UUID retryId = retryAndCommit(original.getId());
        consumeOneJob();

        WorkflowExecution retryRow = rows.get(retryId);
        // AES-GCM은 IV가 매번 달라 같은 평문도 암호문이 다르다 — 비교 대상은 복호 결과다.
        assertThat(retryRow.getTriggerData()).isNotNull().isNotEqualTo(originalCipherText);
        assertThat(workflowExecutionService.decryptTriggerData(retryRow))
            .isEqualTo(Map.of("score", "85", "token", "sk-원본토큰"));

        // 복호된 평문이 실제로 트리거 노드 실행까지 도달했다.
        // 마스킹은 node_runs 저장에만 걸리므로 token은 ***, score는 원값이 남는다.
        String triggerOutput = nodeRunOf(retryId, "t").getOutputJson();
        assertThat(triggerOutput).contains("\"score\":\"85\"", "***");
        assertThat(triggerOutput).doesNotContain("sk-원본토큰");
    }

    @Test
    @DisplayName("원 실행의 마스킹된 트리거 로그가 있어도 트리거는 재실행되어 후속 노드가 복호본을 받는다")
    void 트리거는_스킵되지_않고_복호본으로_다시_실행된다() {
        WorkflowExecution original = givenFailedOriginal();
        givenOriginalNodeRuns(original);
        stubDefinition();

        UUID retryId = retryAndCommit(original.getId());
        consumeOneJob();

        // 원 실행 로그에 t가 SUCCESS로 남아 있어도 스킵 대상에서 빠져 다시 실행된다.
        assertThat(nodeRunOf(retryId, "t").getStatus()).isEqualTo(ExecutionLogStatus.SUCCESS);
        // 결정적 차이: b가 참조하는 {{nodes.t.output.token}}이 복호본이면 원문, 스킵됐다면 ***다.
        assertThat(aiInputs.get("b")).containsEntry("auth", "sk-원본토큰");
    }

    @Test
    @DisplayName("큐를 거친 재처리에서 스킵된 노드의 output이 후속 노드 변수 참조로 해석된다")
    void 스킵노드_output이_후속노드_변수참조로_치환된다() {
        WorkflowExecution original = givenFailedOriginal();
        givenOriginalNodeRuns(original);
        stubDefinition();

        UUID retryId = retryAndCommit(original.getId());
        consumeOneJob();

        // a는 executor 호출 없이 원 실행 output으로 대체됐고, b는 {{nodes.a.output.text}}로 그 값을 받았다.
        assertThat(aiCalls).containsExactly("b");
        assertThat(nodeRunOf(retryId, "a").getStatus()).isEqualTo(ExecutionLogStatus.SKIPPED);
        assertThat(aiInputs.get("b")).containsEntry("prompt", "원본-a-출력");
        assertThat(nodeRunOf(retryId, "b").getInputJson()).contains("원본-a-출력");
    }

    @Test
    @DisplayName("Redis 장애로 큐를 못 쓰면 폴백 직접 실행이 같은 스킵 대상·같은 트리거 입력으로 돈다")
    void 큐_폴백_경로도_같은_결과를_낸다() {
        when(container.isRunning()).thenReturn(false);
        WorkflowExecution original = givenFailedOriginal();
        givenOriginalNodeRuns(original);
        stubDefinition();

        UUID retryId = retryAndCommit(original.getId());

        // 큐를 아예 타지 않았는데도 결과는 큐 경유와 동일하다.
        assertThat(stream).isEmpty();
        assertThat(rows.get(retryId).getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(aiCalls).containsExactly("b");
        assertThat(nodeRunOf(retryId, "a").getStatus()).isEqualTo(ExecutionLogStatus.SKIPPED);
        assertThat(aiInputs.get("b")).containsEntry("prompt", "원본-a-출력");
        assertThat(nodeRunOf(retryId, "t").getOutputJson()).contains("\"score\":\"85\"");
    }

    @Test
    @DisplayName("완료된 재처리 잡이 회수돼 재배달되면 다시 실행하지 않는다(at-least-once 계약)")
    void 완료된_재처리잡_재배달은_중복실행되지_않는다() {
        WorkflowExecution original = givenFailedOriginal();
        givenOriginalNodeRuns(original);
        stubDefinition();

        UUID retryId = retryAndCommit(original.getId());
        MapRecord<String, String, String> job = consumeOneJob();
        assertThat(rows.get(retryId).getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        int nodeRunsAfterFirstRun = nodeRuns.size();

        // 회수 재배달 — 같은 레코드가 다시 온다.
        worker.onMessage(job);

        // 종료 상태 가드가 걸려 노드 부작용이 되풀이되지 않고, ack만 다시 나간다.
        assertThat(aiCalls).containsExactly("b");
        assertThat(nodeRuns).hasSize(nodeRunsAfterFirstRun);
        verify(streamOperations, times(2)).acknowledge(STREAM_KEY, GROUP, job.getId());
    }
}
