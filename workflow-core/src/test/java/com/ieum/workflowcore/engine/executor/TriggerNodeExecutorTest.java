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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TriggerNodeExecutorTest {

    private final TriggerNodeExecutor executor = new TriggerNodeExecutor();

    private static ExecutionCursor cursorWith(String nodeId, Map<String, Object> planted) {
        ExecutionContext context = new ExecutionContext();
        context.setNodeOutput(nodeId, planted);
        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setAllNodes(List.of());
        cursor.setAllEdges(List.of());
        cursor.setContext(context);
        return cursor;
    }

    @ParameterizedTest
    @ValueSource(strings = {"GMAIL_NEW_EMAIL", "GITHUB_NEW_ISSUE"})
    @DisplayName("앱 트리거 출력 = 심어 둔 정규화 페이로드 그대로 — config(query·repoId·triggerType)는 섞이지 않는다")
    void appTriggerOutputsPlantedPayload(String triggerType) {
        Map<String, Object> config = new HashMap<>(Map.of(
            "triggerType", triggerType, "query", "from:boss@x.com", "repoId", 1));
        Node node = new Node("t1", NodeType.TRIGGER, "트리거", config);
        Map<String, Object> payload = Map.of("subject", "안녕", "from", "a@b.c", "labels", List.of("INBOX"));

        ExecutorResult result = executor.execute(node, new HashMap<>(config), cursorWith("t1", payload));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo(payload);
    }

    @Test
    @DisplayName("MANUAL·WEBHOOK은 기존대로 치환된 config(input)를 출력한다 — 회귀")
    void manualStillEchoesInput() {
        Map<String, Object> config = new HashMap<>(Map.of("triggerType", "MANUAL", "x", "{{nodes.t1.output.a}}"));
        Node node = new Node("t1", NodeType.TRIGGER, "트리거", config);
        Map<String, Object> input = Map.of("triggerType", "MANUAL", "x", "rendered");

        ExecutorResult result = executor.execute(node, input, cursorWith("t1", Map.of("a", "rendered")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo(input);
    }
}
