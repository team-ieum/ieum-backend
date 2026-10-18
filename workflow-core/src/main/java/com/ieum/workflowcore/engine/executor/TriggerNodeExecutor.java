package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.trigger.TriggerHandler;
import com.ieum.workflowcore.trigger.TriggerHandlerRegistry;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * TRIGGER 노드 Executor.
 *
 * <p>워크플로우의 진입점으로, {@link com.ieum.workflowcore.engine.SyncExecutionRuntime}이
 * triggerData를 트리거 노드 출력 자리에 심은 뒤 이 Executor를 호출한다 — 재처리 때도 TRIGGER 노드는 스킵하지 않고
 * 다시 실행한다(node_runs의 마스킹된 출력 대신 복호된 triggerData로 같은 출력을 만든다).
 * 트리거 종류는 노드 {@code event}의 카탈로그 {@code trigger_type}이다(없거나 트리거 event가 아니면 MANUAL).
 * 출력은 그 종류의 {@link TriggerHandler#output}이 만든다 — 여기엔 종류별 분기가 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerNodeExecutor implements NodeExecutor {

    private final EventCatalog eventCatalog;
    private final TriggerHandlerRegistry triggerHandlers;

    @Override
    public NodeType getNodeType() {
        return NodeType.TRIGGER;
    }

    @Override
    public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
        long startTime = System.currentTimeMillis();
        log.info("[TriggerExecutor] 노드 실행 — nodeId: {}", node.getId());

        try {
            TriggerType triggerType = eventCatalog.triggerTypeOf(node.getEvent()).orElse(TriggerType.MANUAL);
            // getNodeOutput은 심은 값이 없으면 빈 Map이다(null 아님)
            Map<String, Object> output = triggerHandlers.get(triggerType).output(
                node, input == null ? Map.of() : input, cursor.getContext().getNodeOutput(node.getId()));
            log.debug("[TriggerExecutor] {} 트리거 — outputKeys: {}", triggerType, output.keySet());
            return ExecutorResult.success(output, System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("[TriggerExecutor] 실행 실패 — nodeId: {}", node.getId(), e);
            return ExecutorResult.failure(e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
