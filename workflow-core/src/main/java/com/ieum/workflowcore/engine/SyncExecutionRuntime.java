package com.ieum.workflowcore.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowExecutionLog;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.event.ExecutionEvent;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.engine.executor.NodeExecutor;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import jakarta.annotation.PostConstruct;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 워크플로우를 동기식으로 순차 실행하는 런타임.
 *
 * <p>TRIGGER 노드부터 시작해 {@link ExecutionCursor}가 반환하는 다음 노드를 따라
 * 마지막 노드까지 순회하며 각 노드를 {@link NodeExecutor}에 위임한다.
 * 노드별 실행 결과는 {@link WorkflowExecutionLog}로 저장된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncExecutionRuntime {

    private final ObjectMapper objectMapper;
    private final WorkflowExecutionLogRepository executionLogRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final WorkflowCrudService workflowCrudService;
    private final ExecutionEventPublisher eventPublisher;
    /** @Component로 등록된 모든 NodeExecutor 구현체를 Spring이 자동 주입 */
    private final List<NodeExecutor> nodeExecutors;

    /** 노드 타입 → 실행 전략. @PostConstruct에서 nodeExecutors로부터 구성된다. */
    private final Map<NodeType, NodeExecutor> executorMap = new EnumMap<>(NodeType.class);

    /** fan-out 병렬 실행 시 동시에 실행할 노드 수(워커 스레드 풀 크기). */
    @Value("${workflow.execution.parallelism:4}")
    private int parallelism;

    /** 워커 스레드가 메인으로 돌려주는 노드 실행 결과 묶음. */
    private record NodeOutcome(Node node, Map<String, Object> input,
                               ExecutorResult result, long durationMs) {}

    @PostConstruct
    void initExecutorMap() {
        nodeExecutors.forEach(e -> executorMap.put(e.getNodeType(), e));
        log.info("[Runtime] 등록된 NodeExecutor: {}", executorMap.keySet());
    }

    /**
     * 워크플로우 버전을 파싱하여 동기적으로 실행한다.
     *
     * @param workflowVersion 실행할 버전 (mongoDefinitionId로 MongoDB에서 nodes/edges 로드)
     * @param executionId     DB에 저장된 실행 인스턴스 ID (fresh 조회로 detached 문제 방지)
     * @param triggerData     트리거가 전달한 초기 데이터 (TRIGGER 노드 output에 저장)
     */
    public void execute(
        WorkflowVersion workflowVersion,
        UUID executionId,
        Map<String, Object> triggerData
    ) throws Exception {

        WorkflowExecution execution = workflowExecutionRepository.findWithWorkflowById(executionId)
            .orElseThrow(() -> new CustomException(ErrorCode.EXECUTION_NOT_FOUND));

        log.info("[Runtime] 워크플로우 실행 시작 — executionId: {}, versionId: {}",
            execution.getId(), workflowVersion.getId());

        // 1. MongoDB에서 노드/엣지 로드 및 TRIGGER 검증 (start() 이전 — 실패 시 FAILED로 전환)
        List<Node> nodes;
        List<Edge> edges;
        Node triggerNode;
        try {
            WorkflowDefinitionDocument definition = workflowCrudService.loadDefinition(workflowVersion);
            nodes = objectMapper.convertValue(definition.getNodes(), new TypeReference<>() {});
            edges = objectMapper.convertValue(definition.getEdges(), new TypeReference<>() {});
            triggerNode = nodes.stream()
                .filter(n -> n.getType() == NodeType.TRIGGER)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("TRIGGER 노드가 없습니다."));
        } catch (Exception e) {
            log.error("[Runtime] 워크플로우 초기화 실패 — executionId: {}, error: {}",
                execution.getId(), e.getMessage());
            execution.fail();
            workflowExecutionRepository.save(execution);
            eventPublisher.publish(executionId,
                ExecutionEvent.executionCompleted(ExecutionStatus.FAILED));
            eventPublisher.complete(executionId);
            throw e;
        }

        try {
            // 2. ExecutionCursor 초기화
            ExecutionCursor cursor = new ExecutionCursor();
            cursor.setAllNodes(nodes);
            cursor.setAllEdges(edges);
            ExecutionContext context = new ExecutionContext();
            context.setUserId(execution.getWorkflow().getUserId());
            cursor.setContext(context);

            // 트리거 입력 데이터 보존(트리거 노드 실행 입력으로 사용)
            cursor.getContext().setNodeOutput(triggerNode.getId(),
                triggerData != null ? triggerData : new HashMap<>());

            // 2-1. 구조적 순환 검사(위상정렬 불가 = 사이클)
            assertNoCycle(nodes, edges);

            // 2-2. 모든 노드 Executor 존재 사전 검증 (디스패치 도중 throw로 인한 고아 태스크 방지)
            for (Node n : nodes) {
                if (!executorMap.containsKey(n.getType())) {
                    throw new IllegalStateException("NodeExecutor 없음 — type: " + n.getType());
                }
            }

            // 3. RUNNING 상태로 전환
            execution.start();
            workflowExecutionRepository.save(execution);

            // 4. 위상 스케줄러 상태: pending=미해소 incoming 수, hasLive=live 입력 보유 여부
            Map<String, Integer> pending = new HashMap<>();
            Map<String, Boolean> hasLive = new HashMap<>();
            for (Node n : nodes) {
                pending.put(n.getId(), cursor.incomingEdges(n.getId()).size());
                hasLive.put(n.getId(), false);
            }
            Set<String> resolved = new HashSet<>();   // 실행 완료 ∪ skip

            // 5. fan-out 병렬 실행 (워커=노드실행, 메인=상태/JPA 독점)
            // ExecutorService는 try-with-resources로 관리 — 블록 종료 시 close()가 자동 shutdown+종료 대기.
            boolean failed = false;
            try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallelism))) {
                CompletionService<NodeOutcome> completion = new ExecutorCompletionService<>(pool);
                int inFlight = 0;
                try {
                    Deque<Node> ready = new ArrayDeque<>();
                    ready.add(triggerNode);   // 트리거는 incoming 0 → 최초 ready
                    inFlight += dispatch(ready, cursor, completion, executionId);

                    while (inFlight > 0) {
                        NodeOutcome outcome = completion.take().get();
                        inFlight--;
                        Node node = outcome.node();
                        ExecutorResult result = outcome.result();
                        long durMs = outcome.durationMs();
                        log.info("[Runtime] 노드 완료 — nodeId: {}, success: {}, durationMs: {}",
                            node.getId(), result.isSuccess(), durMs);

                        // 5-1. 실행 로그 저장(메인 스레드)
                        saveExecutionLog(execution, node, outcome.input(), result, durMs);

                        // 5-2. 실패 시 전체 중단(신규 디스패치 멈춤 → finally에서 in-flight 드레인)
                        if (!result.isSuccess()) {
                            log.error("[Runtime] 노드 실패로 워크플로우 중단 — nodeId: {}, error: {}",
                                node.getId(), result.getErrorMessage());
                            eventPublisher.publish(executionId, ExecutionEvent.nodeFailed(
                                node.getId(), node.getType(), result.getErrorMessage(), durMs));
                            failed = true;
                            break;
                        }

                        // 5-3. 성공 처리: 이벤트 + 컨텍스트 저장
                        eventPublisher.publish(executionId,
                            ExecutionEvent.nodeCompleted(node.getId(), node.getType(), durMs));
                        cursor.updateContext(node.getId(), result.getOutput());
                        resolved.add(node.getId());

                        // 5-4. 엣지 전파 → 새로 준비된(모든 입력 해소+live) 노드 디스패치
                        Deque<Node> newReady = new ArrayDeque<>();
                        propagate(node, cursor, pending, hasLive, resolved, newReady);
                        inFlight += dispatch(newReady, cursor, completion, executionId);
                    }
                } finally {
                    // 실패/예외 시 남은 in-flight 작업 드레인(완료 대기) 후 close()로 풀 종료.
                    // 드레인된 노드는 이미 실행돼 부수효과가 발생했으므로 성공/실패 무관하게 로그를 남긴다(감사).
                    // (외부 AI/HTTP 호출은 강제 취소가 불가하므로 인터럽트 대신 드레인)
                    while (inFlight > 0) {
                        try {
                            NodeOutcome drained = completion.take().get();
                            saveExecutionLog(execution, drained.node(), drained.input(),
                                drained.result(), drained.durationMs());
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Exception ex) {
                            log.debug("[Runtime] 드레인 중 워커 결과 회수 실패 — 무시", ex);
                        }
                        inFlight--;
                    }
                }
            }

            // 6. 종료 처리
            if (failed) {
                execution.fail();
                workflowExecutionRepository.save(execution);
                eventPublisher.publish(executionId,
                    ExecutionEvent.executionCompleted(ExecutionStatus.FAILED));
                return;
            }

            execution.complete();
            workflowExecutionRepository.save(execution);
            log.info("[Runtime] 워크플로우 성공 — executionId: {}", execution.getId());
            eventPublisher.publish(executionId,
                ExecutionEvent.executionCompleted(ExecutionStatus.SUCCESS));

        } catch (Exception e) {
            log.error("[Runtime] 워크플로우 실행 중 예외 — executionId: {}", execution.getId(), e);
            // 이미 종료(SUCCESS/FAILED)된 게 아니면 — RUNNING 전환 이전(Cursor 초기화 등)에서
            // 던진 경우(PENDING)까지 — FAILED로 전환한다.
            if (execution.getStatus() != ExecutionStatus.SUCCESS
                    && execution.getStatus() != ExecutionStatus.FAILED) {
                execution.fail();
                workflowExecutionRepository.save(execution);
            }
            eventPublisher.publish(executionId,
                ExecutionEvent.executionCompleted(ExecutionStatus.FAILED));
            throw e;
        } finally {
            // 성공·노드 실패(return)·예외 등 모든 종료 경로에서 SSE 스트림을 닫는다.
            eventPublisher.complete(executionId);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // fan-out 위상 스케줄러 보조
    // ──────────────────────────────────────────────────────────────────────

    /**
     * ready 큐의 노드들을 워커 풀에 제출한다(병렬 실행 시작).
     * 입력 변수 치환·nodeStarted 이벤트는 메인 스레드에서 수행하고, 워커는 executor.execute만 실행한다.
     * 이 노드가 ready가 된 시점에는 모든 live 부모가 완료되어 컨텍스트에 output이 존재한다.
     *
     * @return 제출한 노드 수
     */
    private int dispatch(Deque<Node> ready, ExecutionCursor cursor,
                         CompletionService<NodeOutcome> completion, UUID executionId) {
        int count = 0;
        while (!ready.isEmpty()) {
            Node node = ready.poll();
            // executor 존재는 execute() 진입부에서 사전 검증됨 — 여기선 안전망.
            NodeExecutor executor = executorMap.get(node.getType());
            if (executor == null) {
                throw new IllegalStateException("NodeExecutor 없음 — type: " + node.getType());
            }
            Map<String, Object> input = prepareNodeInput(node, cursor);
            log.info("[Runtime] 노드 실행 — nodeId: {}, type: {}", node.getId(), node.getType());
            eventPublisher.publish(executionId,
                ExecutionEvent.nodeStarted(node.getId(), node.getType()));
            completion.submit(() -> {
                long t = System.currentTimeMillis();
                ExecutorResult r;
                try {
                    r = executor.execute(node, input, cursor);
                } catch (Exception ex) {
                    r = ExecutorResult.failure(ex.toString(), System.currentTimeMillis() - t);
                }
                return new NodeOutcome(node, input, r, System.currentTimeMillis() - t);
            });
            count++;
        }
        return count;
    }

    /**
     * 방금 성공한 노드의 outgoing 엣지를 해소하고, 모든 입력이 해소되어 실행 가능한 노드를 readyOut에 담는다.
     * CONDITION은 매칭된 분기만 live이며, 모든 입력이 dead인 노드는 skip하고 그 하위로 dead를 전파한다(가지치기).
     */
    private void propagate(Node node, ExecutionCursor cursor,
                           Map<String, Integer> pending, Map<String, Boolean> hasLive,
                           Set<String> resolved, Deque<Node> readyOut) {
        List<Edge> live = cursor.liveOutgoingEdges(node);
        Deque<String> deadSources = new ArrayDeque<>();
        resolveEdges(node.getId(), live, true, cursor, pending, hasLive, resolved, readyOut, deadSources);
        while (!deadSources.isEmpty()) {
            String deadId = deadSources.poll();
            resolveEdges(deadId, List.of(), false, cursor, pending, hasLive, resolved, readyOut, deadSources);
        }
    }

    /** source 노드의 각 outgoing 엣지에 대해 target의 pending을 감소시키고, 해소 완료 시 ready/skip을 결정한다. */
    private void resolveEdges(String sourceId, List<Edge> liveEdges, boolean sourceLive,
                              ExecutionCursor cursor, Map<String, Integer> pending,
                              Map<String, Boolean> hasLive, Set<String> resolved,
                              Deque<Node> readyOut, Deque<String> deadSources) {
        for (Edge e : cursor.outgoingEdges(sourceId)) {
            String target = e.getTarget();
            Integer p = pending.get(target);
            if (p == null || resolved.contains(target)) {
                continue;
            }
            if (sourceLive && liveEdges.contains(e)) {
                hasLive.put(target, true);
            }
            pending.put(target, p - 1);
            if (pending.get(target) == 0) {
                if (Boolean.TRUE.equals(hasLive.get(target))) {
                    Node t = cursor.findNode(target);
                    if (t != null) {
                        readyOut.add(t);
                    }
                } else {
                    // 모든 입력이 dead → 노드 skip 후 하위로 dead 전파
                    resolved.add(target);
                    log.debug("[Runtime] 노드 skip(죽은 분기) — nodeId: {}", target);
                    deadSources.add(target);
                }
            }
        }
    }

    /** 워크플로우 그래프에 구조적 사이클이 있으면 예외를 던진다(Kahn 위상정렬 기반). */
    private void assertNoCycle(List<Node> nodes, List<Edge> edges) {
        Map<String, Integer> indeg = new HashMap<>();
        for (Node n : nodes) {
            indeg.put(n.getId(), 0);
        }
        for (Edge e : edges) {
            if (indeg.containsKey(e.getTarget())) {
                indeg.merge(e.getTarget(), 1, Integer::sum);
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        indeg.forEach((id, d) -> {
            if (d == 0) {
                queue.add(id);
            }
        });
        int processed = 0;
        while (!queue.isEmpty()) {
            String id = queue.poll();
            processed++;
            for (Edge e : edges) {
                if (e.getSource().equals(id) && indeg.containsKey(e.getTarget())) {
                    if (indeg.merge(e.getTarget(), -1, Integer::sum) == 0) {
                        queue.add(e.getTarget());
                    }
                }
            }
        }
        if (processed < nodes.size()) {
            throw new IllegalStateException("순환 참조 감지 — 워크플로우 그래프에 사이클이 있습니다.");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 보조 메서드
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 노드의 config를 복사한 뒤 모든 String 값에 변수 치환을 적용한다.
     * Map/List 타입의 중첩 값도 재귀적으로 처리한다.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> prepareNodeInput(Node node, ExecutionCursor cursor) {
        if (node.getConfig() == null) {
            return new HashMap<>();
        }
        return renderMap(node.getConfig(), cursor);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> renderMap(Map<String, Object> source, ExecutionCursor cursor) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            result.put(entry.getKey(), renderValue(entry.getValue(), cursor));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object renderValue(Object value, ExecutionCursor cursor) {
        if (value instanceof String s) {
            return cursor.renderVariables(s);
        }
        if (value instanceof Map<?, ?> map) {
            return renderMap((Map<String, Object>) map, cursor);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> renderValue(item, cursor)).toList();
        }
        return value;
    }

    private static final Set<String> SENSITIVE_KEYS =
        Set.of("apiKey", "api_key", "token", "secret", "password", "Authorization");

    private Map<String, Object> maskSensitiveFields(Map<String, Object> data) {
        if (data == null) return null;
        Map<String, Object> masked = new java.util.LinkedHashMap<>();
        data.forEach((k, v) -> {
            if (SENSITIVE_KEYS.stream().anyMatch(s -> k.toLowerCase().contains(s.toLowerCase()))) {
                masked.put(k, "***");
            } else {
                masked.put(k, v);
            }
        });
        return masked;
    }

    private void saveExecutionLog(
        WorkflowExecution execution,
        Node node,
        Map<String, Object> input,
        ExecutorResult result,
        long durationMs
    ) {
        try {
            String inputJson = objectMapper.writeValueAsString(maskSensitiveFields(input));
            String outputJson = result.isSuccess()
                ? objectMapper.writeValueAsString(maskSensitiveFields(result.getOutput()))
                : null;

            WorkflowExecutionLog logEntry = WorkflowExecutionLog.builder()
                .execution(execution)
                .nodeId(node.getId())
                .nodeType(node.getType())
                .status(result.isSuccess() ? ExecutionLogStatus.SUCCESS : ExecutionLogStatus.FAILED)
                .inputJson(inputJson)
                .outputJson(outputJson)
                .errorMessage(result.getErrorMessage())
                .durationMs(durationMs)
                .build();

            executionLogRepository.save(logEntry);
        } catch (Exception e) {
            // 로그 저장 실패가 실행 전체를 중단시키지 않도록 경고만 기록
            log.warn("[Runtime] 실행 로그 저장 실패 — nodeId: {}", node.getId(), e);
        }
    }
}
