package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * TRIGGER 노드 Executor.
 *
 * <p>워크플로우의 진입점으로, {@link com.ieum.workflowcore.engine.SyncExecutionRuntime}이
 * triggerData를 컨텍스트에 주입한 뒤 이 Executor를 호출하지 않는다.
 * TRIGGER 노드는 루프에서 첫 번째로 만나는 노드이므로 input을 그대로 output으로 전달하는 역할만 한다.
 */
@Slf4j
@Component
public class TriggerNodeExecutor implements NodeExecutor {

    @Override
    public NodeType getNodeType() {
        return NodeType.TRIGGER;
    }

    @Override
    public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
        long startTime = System.currentTimeMillis();
        log.info("[TriggerExecutor] 노드 실행 — nodeId: {}", node.getId());

        try {
            Map<String, Object> output = new HashMap<>();
            String triggerType = (String) node.getConfig().getOrDefault("triggerType", "MANUAL");

            switch (triggerType) {
                case "SCHEDULE" -> {
                    output.put("triggeredAt",
                        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    log.debug("[TriggerExecutor] SCHEDULE 트리거 — triggeredAt: {}", output.get("triggeredAt"));
                }
                default -> {
                    // WEBHOOK, MANUAL: 입력 데이터를 그대로 output으로 전달
                    if (input != null) {
                        output.putAll(input);
                    }
                    log.debug("[TriggerExecutor] {} 트리거 — outputKeys: {}", triggerType, output.keySet());
                }
            }

            return ExecutorResult.success(output, System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("[TriggerExecutor] 실행 실패 — nodeId: {}", node.getId(), e);
            return ExecutorResult.failure(e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
