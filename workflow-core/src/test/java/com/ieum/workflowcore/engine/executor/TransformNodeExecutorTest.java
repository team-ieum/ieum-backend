package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionContext;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransformNodeExecutorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T04:30:00Z"), ConditionValues.KST); // KST 13:30 토
    private final TransformNodeExecutor executor = new TransformNodeExecutor(CLOCK);

    /** 런타임처럼 config를 renderDeep한 사본을 input으로 넘긴다. nodeConfig는 노드 원본 config. */
    @SuppressWarnings("unchecked")
    private ExecutorResult run(Map<String, Object> prevOutput, String event,
                               Map<String, Object> nodeConfig, Map<String, Object> config) {
        ExecutionContext context = new ExecutionContext();
        context.setNodeOutput("p", prevOutput);
        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setAllNodes(List.of());
        cursor.setAllEdges(List.of());
        cursor.setContext(context);
        Map<String, Object> input = (Map<String, Object>) cursor.renderDeep(config);
        return executor.execute(new Node("f", NodeType.TRANSFORM, "포맷", nodeConfig, event), input, cursor);
    }

    private static Map<String, Object> mappingConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("mappings", Map.of("a", "{{nodes.p.output.x}}"));
        return config;
    }

    @Test
    @DisplayName("transform.map은 지금처럼 node config의 mappings를 치환한다")
    void transformMapUnchanged() {
        ExecutorResult result = run(Map.of("x", "1"), "transform.map", mappingConfig(), mappingConfig());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).containsExactlyEntriesOf(Map.of("a", "1"));
    }

    @Test
    @DisplayName("event 없는 TRANSFORM도 매핑으로 처리한다")
    void noEventIsMapping() {
        ExecutorResult result = run(Map.of("x", "1"), null, mappingConfig(), mappingConfig());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).containsExactlyEntriesOf(Map.of("a", "1"));
    }

    @Test
    @DisplayName("포맷터는 input만 읽고 참조식을 다시 치환하지 않는다")
    void formatterReadsInputOnly() {
        Map<String, Object> config = new HashMap<>();
        config.put("input", "  {{nodes.p.output.x}}  ");

        ExecutorResult result = run(Map.of("x", "{{nodes.p.output.secret}}", "secret", "비밀"),
            "formatter.text_trim", new HashMap<>(), config);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).containsEntry("output", "{{nodes.p.output.secret}}");
    }

    @Test
    @DisplayName("변환 실패는 '<title>: <이유> (입력: '<50자 요약>…')'로 감싼다")
    void formatterFailureIsWrapped() {
        Map<String, Object> config = new HashMap<>();
        config.put("input", "a".repeat(FormatterInputs.MAX_TEXT + 1));

        ExecutorResult result = run(Map.of(), "formatter.text_trim", new HashMap<>(), config);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage())
            .isEqualTo("공백 정리: 'input' 칸이 10만 자를 넘어요 (입력: '" + "a".repeat(50) + "…')");
    }

    @Test
    @DisplayName("모르는 포맷터 event는 노드 실패다")
    void unknownFormatterFails() {
        Map<String, Object> config = new HashMap<>();
        config.put("input", "a");

        ExecutorResult result = run(Map.of(), "formatter.nope", new HashMap<>(), config);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("지원하지 않는 포맷터예요: formatter.nope");
    }
}
