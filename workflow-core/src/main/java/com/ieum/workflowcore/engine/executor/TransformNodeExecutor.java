package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * TRANSFORM 노드 Executor.
 *
 * <p>config의 {@code mappings}에 선언된 키-값 매핑에 변수 치환을 적용하여
 * 새로운 output Map을 생성한다.
 *
 * <p>예시 config:
 * <pre>{@code
 * "mappings": {
 *   "userId":   "{{nodes.node-1.output.data.user.id}}",
 *   "greeting": "Welcome {{nodes.node-1.output.data.user.name}}!"
 * }
 * }</pre>
 */
@Slf4j
@Component
public class TransformNodeExecutor implements NodeExecutor {

    @Override
    public NodeType getNodeType() {
        return NodeType.TRANSFORM;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
        long startTime = System.currentTimeMillis();
        log.info("[TransformExecutor] 노드 실행 — nodeId: {}", node.getId());

        try {
            Map<String, Object> config = node.getConfig();
            Map<String, Object> mappings = (Map<String, Object>) config.get("mappings");

            Map<String, Object> output = new HashMap<>();
            if (mappings != null) {
                mappings.forEach((key, value) -> {
                    if (value instanceof String s) {
                        output.put(key, cursor.renderVariables(s));
                    } else {
                        output.put(key, value);
                    }
                });
            }

            log.debug("[TransformExecutor] 변환 완료 — outputKeys: {}", output.keySet());
            return ExecutorResult.success(output, System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("[TransformExecutor] 실행 실패 — nodeId: {}", node.getId(), e);
            return ExecutorResult.failure(e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
