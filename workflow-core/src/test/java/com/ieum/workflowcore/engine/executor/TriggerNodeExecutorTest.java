package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.catalog.EventCatalog;
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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class TriggerNodeExecutorTest {

    private static final EventCatalog CATALOG = EventCatalog.loadFromClasspath();

    private final TriggerNodeExecutor executor = new TriggerNodeExecutor(CATALOG);

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
    @ValueSource(strings = {"gmail.new_email", "github.new_issue"})
    @DisplayName("앱 트리거 event 출력 = 심어 둔 정규화 페이로드 그대로 — config(query·repoId)는 섞이지 않는다")
    void appTriggerOutputsPlantedPayload(String event) {
        Map<String, Object> config = new HashMap<>(Map.of("query", "from:boss@x.com", "repoId", 1));
        Node node = new Node("t1", NodeType.TRIGGER, "트리거", config, event);
        Map<String, Object> payload = Map.of("subject", "안녕", "from", "a@b.c", "labels", List.of("INBOX"));

        ExecutorResult result = executor.execute(node, new HashMap<>(config), cursorWith("t1", payload));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo(payload);
    }

    @Test
    @DisplayName("webhook.catch 출력 = 심어 둔 수신 페이로드 그대로 — config(includeRaw)는 섞이지 않는다")
    void webhookOutputsPlantedPayload() {
        Map<String, Object> config = new HashMap<>(Map.of("includeRaw", true));
        Node node = new Node("t1", NodeType.TRIGGER, "트리거", config, "webhook.catch");
        Map<String, Object> payload = Map.of("action", "opened", "issue", Map.of("number", 7));

        ExecutorResult result = executor.execute(node, new HashMap<>(config), cursorWith("t1", payload));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo(payload);
    }

    @Test
    @DisplayName("config가 {}인 webhook.catch도 페이로드를 낸다 — 옛 config.triggerType 판정에선 MANUAL로 빠져 페이로드를 잃었다")
    void webhookWithEmptyConfigOutputsPlantedPayload() {
        Node node = new Node("t1", NodeType.TRIGGER, "트리거", new HashMap<>(), "webhook.catch");
        Map<String, Object> payload = Map.of("orderId", "ORD-1");

        ExecutorResult result = executor.execute(node, new HashMap<>(), cursorWith("t1", payload));

        assertThat(result.getOutput()).isEqualTo(payload);
    }

    @Test
    @DisplayName("manual.run은 치환된 config(input)를 출력한다 — 회귀")
    void manualStillEchoesInput() {
        Map<String, Object> config = new HashMap<>(Map.of("x", "{{nodes.t1.output.a}}"));
        Node node = new Node("t1", NodeType.TRIGGER, "트리거", config, "manual.run");
        Map<String, Object> input = Map.of("x", "rendered");

        ExecutorResult result = executor.execute(node, input, cursorWith("t1", Map.of("a", "rendered")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo(input);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"nope.unknown", "gmail.send_email"})
    @DisplayName("event 없음·모르는 event·트리거가 아닌 event → MANUAL처럼 input을 낸다(심어 둔 페이로드를 내지 않는다)")
    void eventWithoutTriggerTypeFallsBackToManual(String event) {
        Node node = new Node("t1", NodeType.TRIGGER, "트리거", new HashMap<>(), event);
        Map<String, Object> input = Map.of("k", "v");

        ExecutorResult result = executor.execute(node, input, cursorWith("t1", Map.of("planted", true)));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo(input);
    }
}
