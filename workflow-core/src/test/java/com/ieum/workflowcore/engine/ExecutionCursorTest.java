package com.ieum.workflowcore.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.domain.enums.NodeType;
import java.util.HashMap;
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
}
