package com.ieum.workflowcore.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.domain.enums.NodeType;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExecutionCursorTest {

    private ExecutionCursor cursor(List<Node> nodes, List<Edge> edges) {
        ExecutionCursor c = new ExecutionCursor();
        c.setAllNodes(nodes);
        c.setAllEdges(edges);
        c.setContext(new ExecutionContext());
        return c;
    }

    private Node node(String id, NodeType type) {
        return new Node(id, type, id, new java.util.HashMap<>());
    }

    @Test
    @DisplayName("outgoing/incoming 엣지를 노드 기준으로 반환한다")
    void outgoing_incoming_edges() {
        ExecutionCursor c = cursor(
            List.of(node("a", NodeType.TRIGGER), node("b", NodeType.AI), node("c", NodeType.AI)),
            List.of(new Edge("a", "b", null), new Edge("a", "c", null))
        );
        assertThat(c.outgoingEdges("a")).hasSize(2);
        assertThat(c.incomingEdges("b")).hasSize(1);
        assertThat(c.incomingEdges("a")).isEmpty();
    }

    @Test
    @DisplayName("일반 노드는 모든 outgoing 엣지가 live")
    void live_edges_for_normal_node() {
        ExecutionCursor c = cursor(
            List.of(node("a", NodeType.AI), node("b", NodeType.AI), node("c", NodeType.AI)),
            List.of(new Edge("a", "b", null), new Edge("a", "c", null))
        );
        assertThat(c.liveOutgoingEdges(c.findNode("a"))).hasSize(2);
    }

    @Test
    @DisplayName("CONDITION 노드는 result에 맞는 conditionType 엣지만 live")
    void live_edges_for_condition_node() {
        ExecutionCursor c = cursor(
            List.of(node("cond", NodeType.CONDITION), node("t", NodeType.AI), node("f", NodeType.AI)),
            List.of(new Edge("cond", "t", "true"), new Edge("cond", "f", "false"))
        );
        c.getContext().setNodeOutput("cond", Map.of("result", true));
        List<Edge> live = c.liveOutgoingEdges(c.findNode("cond"));
        assertThat(live).hasSize(1);
        assertThat(live.get(0).getTarget()).isEqualTo("t");
    }

    @Test
    @DisplayName("renderDeep은 중첩 Map·List의 참조식을 치환한 가변 복사본을 돌려주고 원본은 그대로 둔다")
    @SuppressWarnings("unchecked")
    void renderDeep_rendersNestedAndReturnsMutableCopy() {
        ExecutionCursor c = cursor(List.of(), List.of());
        c.getContext().setNodeOutput("node-0", Map.of("sheetId", "1Bxi"));
        Map<String, Object> config = new HashMap<>(Map.of("spreadsheet_id", "{{nodes.node-0.output.sheetId}}"));
        Map<String, Object> tool = new HashMap<>(Map.of("name", "builtin:google_sheets_read", "config", config));
        List<Object> original = List.of(tool, 7);  // 원본 리스트는 불변 — 복사본만 가변이어야 한다

        List<Object> rendered = (List<Object>) c.renderDeep(original);

        Map<String, Object> renderedConfig =
            (Map<String, Object>) ((Map<String, Object>) rendered.get(0)).get("config");
        assertThat(renderedConfig.get("spreadsheet_id")).isEqualTo("1Bxi");
        assertThat(rendered.get(1)).isEqualTo(7);
        rendered.add("added");
        renderedConfig.put("webhook_url", "x");
        assertThat(original).hasSize(2);
        assertThat(config).isEqualTo(Map.of("spreadsheet_id", "{{nodes.node-0.output.sheetId}}"));
    }

    private ExecutionCursor cursorWithOutput(String nodeId, Map<String, Object> output) {
        ExecutionCursor c = cursor(List.of(), List.of());
        c.getContext().setNodeOutput(nodeId, output);
        return c;
    }

    @Test
    @DisplayName("경로의 숫자 세그먼트는 List 인덱스로 읽는다 — issues.0.title")
    void renderVariables_numericSegmentIsListIndex() {
        ExecutionCursor c = cursorWithOutput("list", Map.of("issues", List.of(
            Map.of("title", "첫 이슈"), Map.of("title", "둘째 이슈"))));

        assertThat(c.renderVariables("{{nodes.list.output.issues.0.title}}")).isEqualTo("첫 이슈");
        assertThat(c.renderVariables("{{nodes.list.output.issues.1.title}}")).isEqualTo("둘째 이슈");
    }

    @Test
    @DisplayName("범위 밖·int 범위를 넘는 인덱스·없는 키는 예외 없이 빈 문자열이고, 문자열 값에 붙은 숫자 세그먼트는 그 값에서 멈춘다")
    void renderVariables_badIndexIsEmptyNotException() {
        Map<String, Object> output = new HashMap<>();
        output.put("issues", List.of(Map.of("title", "첫 이슈")));
        output.put("empty", List.of());
        output.put("title", "문자열");
        ExecutionCursor c = cursorWithOutput("list", output);

        assertThat(c.renderVariables("{{nodes.list.output.issues.5.title}}")).isEmpty();
        assertThat(c.renderVariables("{{nodes.list.output.empty.0}}")).isEmpty();
        assertThat(c.renderVariables("{{nodes.list.output.issues.99999999999.title}}")).isEmpty();
        assertThat(c.renderVariables("{{nodes.list.output.issues.0.missing}}")).isEmpty();
        // 기존 규칙 유지 — 최종 값에 이미 도달했으면 남은 세그먼트를 무시하고 그 값을 쓴다
        assertThat(c.renderVariables("{{nodes.list.output.title.0}}")).isEqualTo("문자열");
    }

    @Test
    @DisplayName("최종 값이 Map·List면 JSON 문자열로 치환하고, 문자열·숫자·불리언은 기존과 같다")
    void renderVariables_mapAndListBecomeJson() {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("number", 7);
        issue.put("title", "t");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("issue", issue);
        output.put("labels", List.of("a", "b"));
        output.put("count", 7);
        output.put("flag", true);
        output.put("text", "text");
        ExecutionCursor c = cursorWithOutput("n", output);

        assertThat(c.renderVariables("{{nodes.n.output.issue}}")).isEqualTo("{\"number\":7,\"title\":\"t\"}");
        assertThat(c.renderVariables("{{nodes.n.output.labels}}")).isEqualTo("[\"a\",\"b\"]");
        // List에 숫자가 아닌 키를 붙이면 List 전체가 최종 값이다(예전엔 List.toString)
        assertThat(c.renderVariables("{{nodes.n.output.labels.first}}")).isEqualTo("[\"a\",\"b\"]");
        assertThat(c.renderVariables("{{nodes.n.output.count}}")).isEqualTo("7");
        assertThat(c.renderVariables("{{nodes.n.output.flag}}")).isEqualTo("true");
        assertThat(c.renderVariables("{{nodes.n.output.text}}")).isEqualTo("text");
    }

    @Test
    @DisplayName("JSON 안의 $·\\는 치환 때 해석되지 않고 그대로 들어간다")
    void renderVariables_jsonWithDollarAndBackslashSurvives() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("t", "a$1\\b");
        ExecutionCursor c = cursorWithOutput("n", Map.of("m", inner));

        assertThat(c.renderVariables("{{nodes.n.output.m}}")).isEqualTo("{\"t\":\"a$1\\\\b\"}");
    }
}
