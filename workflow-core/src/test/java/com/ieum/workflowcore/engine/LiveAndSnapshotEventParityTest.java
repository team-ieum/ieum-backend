package com.ieum.workflowcore.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.common.util.AesEncryptor;
import com.ieum.workflowcore.config.RetryProperties;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowExecutionLog;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.event.ExecutionEvent;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.engine.event.ExecutionEventType;
import com.ieum.workflowcore.engine.event.NodeEventStatus;
import com.ieum.workflowcore.engine.executor.AlertNotifier;
import com.ieum.workflowcore.engine.executor.IdempotencyStore;
import com.ieum.workflowcore.engine.executor.NodeExecutor;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.repository.WorkflowQueryRepository;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 라이브 SSE 스트림과 늦은 구독용 스냅샷 재생이 <b>같은 모양</b>의 노드 이벤트를 내는지 검증한다.
 *
 * <p>프론트는 {@code nodeId + type}으로 이벤트를 멱등 처리하므로, 두 경로가 같은 노드에 대해
 * 다른 {@code type}·{@code status}를 내면 구독 시점에 따라 화면이 갈린다.
 * 실행 런타임이 실제로 남긴 {@code node_runs} 행을 그대로 스냅샷 조회에 물려 비교한다.
 *
 * <p>{@code NODE_STARTED}는 비교 대상이 아니다 — 재생은 종료된 노드 결과만 되살리는 설계이고,
 * 이는 스킵 노드가 아니라 모든 노드에 해당하는 기존 전제다.
 */
@DisplayName("라이브 스트림 ↔ 스냅샷 재생 이벤트 모양 일치")
class LiveAndSnapshotEventParityTest {

    /** 비교 대상 — 프론트가 노드 상태를 그리는 데 쓰는 값들. */
    private record NodeEventShape(String nodeId, ExecutionEventType type,
                                  NodeEventStatus status, Long durationMs) {}

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID executionId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    private WorkflowExecutionLogRepository logRepository;
    private WorkflowExecutionRepository executionRepository;
    private WorkflowCrudService crudService;
    private ExecutionEventPublisher eventPublisher;
    private WorkflowExecution execution;

    /** 조건 노드 결과만 흉내 내는 최소 Executor. */
    private record FakeExecutor(NodeType type, Map<String, Boolean> conditionResults)
        implements NodeExecutor {

        @Override
        public NodeType getNodeType() {
            return type;
        }

        @Override
        public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
            if (conditionResults.containsKey(node.getId())) {
                return ExecutorResult.success(Map.of("result", conditionResults.get(node.getId())), 1);
            }
            return ExecutorResult.success(Map.of("output", node.getId()), 1);
        }
    }

    private final Map<String, Boolean> conditionResults = new HashMap<>();

    @BeforeEach
    void setUp() {
        logRepository = mock(WorkflowExecutionLogRepository.class);
        executionRepository = mock(WorkflowExecutionRepository.class);
        crudService = mock(WorkflowCrudService.class);
        eventPublisher = mock(ExecutionEventPublisher.class);
        execution = mock(WorkflowExecution.class);
        Workflow workflow = mock(Workflow.class);

        when(workflow.getId()).thenReturn(workflowId);
        when(workflow.getUserId()).thenReturn(UUID.randomUUID());
        when(execution.getWorkflow()).thenReturn(workflow);
        // 실행이 아직 끝나지 않은 상태로 두면 스냅샷에 종료 이벤트가 붙지 않아 노드 이벤트만 남는다.
        when(execution.getStatus()).thenReturn(ExecutionStatus.RUNNING);
        when(execution.getTraceId()).thenReturn("11112222333344445555666677778888");
        when(executionRepository.findWithWorkflowById(executionId)).thenReturn(Optional.of(execution));
        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
    }

    private void runRuntime(Map<String, Map<String, Object>> preCompleted) throws Exception {
        SyncExecutionRuntime runtime = new SyncExecutionRuntime(
            objectMapper, logRepository, executionRepository, crudService, eventPublisher,
            List.of(new FakeExecutor(NodeType.TRIGGER, conditionResults),
                new FakeExecutor(NodeType.AI, conditionResults),
                new FakeExecutor(NodeType.CONDITION, conditionResults)),
            new RetryProperties(), mock(IdempotencyStore.class), mock(AlertNotifier.class));
        runtime.initExecutorMap();
        // 완료 순서를 제출 순서로 고정해 라이브·재생 순서를 결정적으로 비교한다.
        ReflectionTestUtils.setField(runtime, "parallelism", 1);
        runtime.execute(mock(WorkflowVersion.class), executionId, new HashMap<>(), preCompleted);
    }

    /** 라이브로 발행된 이벤트 중 노드 종료 이벤트만 발행 순서대로. */
    private List<NodeEventShape> liveShapes() {
        ArgumentCaptor<ExecutionEvent> captor = ArgumentCaptor.forClass(ExecutionEvent.class);
        verify(eventPublisher, atLeastOnce()).publish(eq(executionId), captor.capture());
        return captor.getAllValues().stream()
            .filter(e -> e.nodeId() != null && e.type() != ExecutionEventType.NODE_STARTED)
            .map(e -> new NodeEventShape(e.nodeId(), e.type(), e.status(), e.durationMs()))
            .toList();
    }

    /** 런타임이 저장한 node_runs 행을 그대로 물려 만든 스냅샷 재생 이벤트. */
    private List<NodeEventShape> snapshotShapes() {
        ArgumentCaptor<WorkflowExecutionLog> captor =
            ArgumentCaptor.forClass(WorkflowExecutionLog.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        when(logRepository.findByExecutionIdOrderByCreatedAtAsc(executionId))
            .thenReturn(captor.getAllValues());

        WorkflowExecutionService service = new WorkflowExecutionService(
            executionRepository, logRepository, mock(WorkflowQueryRepository.class),
            objectMapper, mock(AesEncryptor.class), mock(AlertNotifier.class));

        return service.loadEventSnapshot(workflowId, executionId).events().stream()
            .filter(e -> e.nodeId() != null)
            .map(e -> new NodeEventShape(e.nodeId(), e.type(), e.status(), e.durationMs()))
            .toList();
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
        when(crudService.loadDefinition(any())).thenReturn(
            WorkflowDefinitionDocument.builder().nodes(nodes).edges(edges).build());
    }

    @Test
    @DisplayName("조건 분기로 스킵된 노드: 라이브와 재생이 같은 nodeId·type·status를 낸다")
    void dead_branch_skip_has_same_shape_in_both_paths() throws Exception {
        conditionResults.put("cond", true);
        stubDefinition(
            List.of(node("t", "TRIGGER"), node("cond", "CONDITION"),
                node("yes", "AI"), node("no", "AI")),
            List.of(edge("t", "cond", null), edge("cond", "yes", "true"),
                edge("cond", "no", "false"))
        );

        runRuntime(Map.of());

        List<NodeEventShape> live = liveShapes();
        assertThat(live).containsExactlyElementsOf(snapshotShapes());
        // 두 경로가 "아무 이벤트도 안 낸다"로 일치해 버리는 공허한 통과를 막는다.
        assertThat(live).contains(new NodeEventShape(
            "no", ExecutionEventType.NODE_COMPLETED, NodeEventStatus.SKIPPED, 0L));
    }

    @Test
    @DisplayName("재처리 스킵 노드: 라이브와 재생이 같은 nodeId·type·status를 낸다")
    void precompleted_skip_has_same_shape_in_both_paths() throws Exception {
        stubDefinition(
            List.of(node("t", "TRIGGER"), node("a", "AI"), node("b", "AI")),
            List.of(edge("t", "a", null), edge("a", "b", null))
        );

        runRuntime(Map.of("a", Map.of("output", "원 실행 값")));

        List<NodeEventShape> live = liveShapes();
        assertThat(live).containsExactlyElementsOf(snapshotShapes());
        assertThat(live).contains(new NodeEventShape(
            "a", ExecutionEventType.NODE_COMPLETED, NodeEventStatus.SKIPPED, 0L));
    }

    @Test
    @DisplayName("회귀: 스킵이 없는 성공 실행도 두 경로 모양이 같다")
    void plain_success_has_same_shape_in_both_paths() throws Exception {
        stubDefinition(
            List.of(node("t", "TRIGGER"), node("a", "AI")),
            List.of(edge("t", "a", null))
        );

        runRuntime(Map.of());

        List<NodeEventShape> live = liveShapes();
        assertThat(live).containsExactlyElementsOf(snapshotShapes());
        assertThat(live).extracting(NodeEventShape::status)
            .containsOnly(NodeEventStatus.SUCCESS);
        assertThat(live).extracting(NodeEventShape::nodeId).containsExactly("t", "a");
    }
}
