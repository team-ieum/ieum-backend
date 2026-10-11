package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionContext;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConditionNodeExecutorTest {

    private final ConditionNodeExecutor executor = new ConditionNodeExecutor();

    /** 앞 AI 노드("ai")가 구조화 출력 {urgent: true, count: 4}를 낸 실행 상태에서 조건을 평가한다. */
    private boolean evaluate(String operator, Object left, Object right) {
        Map<String, Object> config = new HashMap<>();   // null 값 허용 — Map.of 금지
        config.put("operator", operator);
        config.put("left", left);
        config.put("right", right);
        ExecutionContext context = new ExecutionContext();
        context.setNodeOutput("ai", Map.of("urgent", true, "count", 4));
        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setAllNodes(List.of());
        cursor.setAllEdges(List.of());
        cursor.setContext(context);

        ExecutorResult result = executor.execute(
            new Node("cond", NodeType.CONDITION, "조건", config), Map.of(), cursor);

        assertThat(result.isSuccess()).isTrue();
        return (Boolean) result.getOutput().get("result");
    }

    @Test
    @DisplayName("치환 결과 \"true\"는 우변 JSON boolean true·문자열 \"true\"와 같다")
    void equals_renderedBooleanMatchesJsonBoolean() {
        assertThat(evaluate("equals", "{{nodes.ai.output.urgent}}", true)).isTrue();
        assertThat(evaluate("equals", "{{nodes.ai.output.urgent}}", "true")).isTrue();
        assertThat(evaluate("notEquals", "{{nodes.ai.output.urgent}}", true)).isFalse();
    }

    @Test
    @DisplayName("치환 결과 \"4\"는 우변 JSON 숫자 4·문자열 \"4\"와 같다 — 표기가 다른 4.0과는 다르다")
    void equals_renderedNumberMatchesJsonNumber() {
        assertThat(evaluate("equals", "{{nodes.ai.output.count}}", 4)).isTrue();
        assertThat(evaluate("equals", "{{nodes.ai.output.count}}", "4")).isTrue();
        assertThat(evaluate("equals", "{{nodes.ai.output.count}}", 4.0)).isFalse();
    }

    @Test
    @DisplayName("대소문자는 구분한다")
    void equals_isCaseSensitive() {
        assertThat(evaluate("equals", "True", "true")).isFalse();
        assertThat(evaluate("notEquals", "True", "true")).isTrue();
    }

    @Test
    @DisplayName("null은 null과만 같다 — 빈 문자열과는 다르다")
    void equals_nullOnlyEqualsNull() {
        assertThat(evaluate("equals", null, null)).isTrue();
        assertThat(evaluate("equals", null, "")).isFalse();
        assertThat(evaluate("equals", "", null)).isFalse();
        assertThat(evaluate("notEquals", null, "")).isTrue();
        assertThat(evaluate("notEquals", null, null)).isFalse();
    }

    @Test
    @DisplayName("기존 문자열끼리 비교는 그대로다")
    void equals_plainStringsUnchanged() {
        assertThat(evaluate("equals", "a", "a")).isTrue();
        assertThat(evaluate("equals", "a", "b")).isFalse();
        assertThat(evaluate("notEquals", "a", "b")).isTrue();
    }
}
