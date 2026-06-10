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
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
            objectMapper, logRepository, executionRepository, crudService, eventPublisher, executors);
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
        verify(execution).fail();
        verify(execution, never()).complete();
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
}
