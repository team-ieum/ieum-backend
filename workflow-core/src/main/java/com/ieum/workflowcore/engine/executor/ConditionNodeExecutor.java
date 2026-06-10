package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CONDITION 노드 Executor.
 *
 * <p>config의 {@code operator}, {@code leftValue}, {@code rightValue}를 평가하여
 * output에 {@code result: true|false}를 반환한다.
 * {@link com.ieum.workflowcore.engine.ExecutionCursor#liveOutgoingEdges}가 이 결과로
 * live 분기 경로를 선택한다.
 *
 * <p>지원 연산자: equals, notEquals, contains, notContains,
 * greaterThan, lessThan, greaterThanOrEqual, lessThanOrEqual, isEmpty, isNotEmpty
 */
@Slf4j
@Component
public class ConditionNodeExecutor implements NodeExecutor {

    @Override
    public NodeType getNodeType() {
        return NodeType.CONDITION;
    }

    @Override
    public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
        long startTime = System.currentTimeMillis();
        log.info("[ConditionExecutor] 노드 실행 — nodeId: {}", node.getId());

        try {
            Map<String, Object> config = node.getConfig();
            String operator = (String) config.get("operator");

            // left/leftValue, right/rightValue 둘 다 허용 (Swagger 예시와 내부 필드명 혼용 대응)
            Object leftValue = config.containsKey("left") ? config.get("left") : config.get("leftValue");
            Object rightValue = config.containsKey("right") ? config.get("right") : config.get("rightValue");

            if (leftValue instanceof String s) {
                leftValue = cursor.renderVariables(s);
            }
            if (rightValue instanceof String s) {
                rightValue = cursor.renderVariables(s);
            }

            boolean result = evaluate(operator, leftValue, rightValue);
            log.info("[ConditionExecutor] 평가 완료 — operator: {}, left: {}, right: {}, result: {}",
                operator, leftValue, rightValue, result);

            return ExecutorResult.success(Map.of("result", result), System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("[ConditionExecutor] 실행 실패 — nodeId: {}", node.getId(), e);
            return ExecutorResult.failure(e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private boolean evaluate(String operator, Object left, Object right) {
        return switch (operator) {
            case "equals" -> java.util.Objects.equals(left, right);
            case "notEquals" -> !java.util.Objects.equals(left, right);
            case "contains" -> left != null && right != null
                && left.toString().contains(right.toString());
            case "notContains" -> left == null || right == null
                || !left.toString().contains(right.toString());
            case "greaterThan" -> toDouble(left) > toDouble(right);
            case "lessThan" -> toDouble(left) < toDouble(right);
            case "greaterThanOrEqual" -> toDouble(left) >= toDouble(right);
            case "lessThanOrEqual" -> toDouble(left) <= toDouble(right);
            case "isEmpty" -> left == null || (left instanceof String s && s.isEmpty());
            case "isNotEmpty" -> left != null && !(left instanceof String s && s.isEmpty());
            default -> throw new IllegalArgumentException("지원하지 않는 연산자: " + operator);
        };
    }

    private double toDouble(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("숫자 비교 대상이 null입니다");
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("숫자로 변환할 수 없는 값: " + value);
        }
    }
}
