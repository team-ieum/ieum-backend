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
import com.ieum.workflowcore.engine.executor.NodeExecutor;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import jakarta.annotation.PostConstruct;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    /** @Component로 등록된 모든 NodeExecutor 구현체를 Spring이 자동 주입 */
    private final List<NodeExecutor> nodeExecutors;

    /** 노드 타입 → 실행 전략. @PostConstruct에서 nodeExecutors로부터 구성된다. */
    private final Map<NodeType, NodeExecutor> executorMap = new EnumMap<>(NodeType.class);

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
            throw e;
        }

        // 2. ExecutionCursor 초기화
        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setAllNodes(nodes);
        cursor.setAllEdges(edges);
        ExecutionContext context = new ExecutionContext();
        context.setUserId(execution.getWorkflow().getUserId());
        cursor.setContext(context);

        cursor.getContext().setNodeOutput(triggerNode.getId(),
            triggerData != null ? triggerData : new HashMap<>());
        cursor.setCurrentNodeId(triggerNode.getId());
        cursor.isCircularReference(triggerNode.getId());

        // 3. RUNNING 상태로 전환
        execution.start();
        workflowExecutionRepository.save(execution);

        try {
            // 5. 노드 순회 루프
            while (cursor.getCurrentNode() != null) {
                Node currentNode = cursor.getCurrentNode();
                log.info("[Runtime] 노드 실행 — nodeId: {}, type: {}",
                    currentNode.getId(), currentNode.getType());

                long nodeStartTime = System.currentTimeMillis();

                // 5-1. 입력값 변수 치환
                Map<String, Object> nodeInput = prepareNodeInput(currentNode, cursor);

                // 5-2. Executor 조회 및 실행
                NodeExecutor executor = executorMap.get(currentNode.getType());
                if (executor == null) {
                    throw new IllegalStateException(
                        "NodeExecutor 없음 — type: " + currentNode.getType());
                }

                ExecutorResult result = executor.execute(currentNode, nodeInput, cursor);
                long nodeDurationMs = System.currentTimeMillis() - nodeStartTime;
                log.info("[Runtime] 노드 완료 — nodeId: {}, success: {}, durationMs: {}",
                    currentNode.getId(), result.isSuccess(), nodeDurationMs);

                // 5-3. 실행 로그 저장
                saveExecutionLog(execution, currentNode, nodeInput, result, nodeDurationMs);

                // 5-4. 실패 시 즉시 중단
                if (!result.isSuccess()) {
                    log.error("[Runtime] 노드 실패로 워크플로우 중단 — nodeId: {}, error: {}",
                        currentNode.getId(), result.getErrorMessage());
                    execution.fail();
                    workflowExecutionRepository.save(execution);
                    return;
                }

                // 5-5. 컨텍스트에 output 저장
                cursor.updateContext(currentNode.getId(), result.getOutput());

                // 5-6. 다음 노드 결정
                Node nextNode = cursor.getNextNode(currentNode);
                if (nextNode == null) {
                    log.info("[Runtime] 마지막 노드 도달 — 실행 완료");
                    break;
                }

                // 5-7. 순환 참조 검사
                if (cursor.isCircularReference(nextNode.getId())) {
                    throw new IllegalStateException(
                        "순환 참조 감지 — nodeId: " + nextNode.getId());
                }

                cursor.setCurrentNodeId(nextNode.getId());
            }

            // 6. 성공 완료
            execution.complete();
            workflowExecutionRepository.save(execution);
            log.info("[Runtime] 워크플로우 성공 — executionId: {}", execution.getId());

        } catch (Exception e) {
            log.error("[Runtime] 워크플로우 실행 중 예외 — executionId: {}", execution.getId(), e);
            if (execution.getStatus() == ExecutionStatus.RUNNING) {
                execution.fail();
                workflowExecutionRepository.save(execution);
            }
            throw e;
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
