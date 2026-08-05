package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.dto.EdgeDto;
import com.ieum.api.workflow.dto.EdgeView;
import com.ieum.api.workflow.dto.NodeDto;
import com.ieum.api.workflow.dto.NodeView;
import com.ieum.api.workflow.dto.WorkflowResponse;
import com.ieum.common.dto.PageResponse;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상세/목록 조회 응답의 노드 계약을 검증한다 (IEUM-BE-60).
 *
 * <p>조회 응답은 요청 DTO({@link NodeDto})가 아니라 {@link NodeView}·{@link EdgeView}가 raw
 * {@code Map}에서 직접 만든다. 저장된 값이 그대로 돌아오는지, 구조가 어긋난 문서가 조회를 무너뜨리지
 * 않는지, 응답 JSON 모양이 요청 DTO와 같은지를 본다. 직렬화 동작 자체가 검증 대상이므로
 * {@link ObjectMapper}는 mock이 아닌 실물을 쓴다.
 */
class WorkflowNodeResponseTest {

    private final WorkflowCrudService workflowCrudService = mock(WorkflowCrudService.class);
    private final WorkflowService workflowService = new WorkflowService(
        workflowCrudService,
        mock(WorkflowExecutionService.class),
        mock(WorkflowExecutionRunner.class),
        mock(ExecutionEventPublisher.class),
        new ObjectMapper());

    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    private WorkflowResponse getWorkflowWithNodes(List<Map<String, Object>> nodes) {
        return getWorkflow(nodes, List.of());
    }

    private WorkflowResponse getWorkflow(List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges) {
        Workflow workflow = mock(Workflow.class);
        WorkflowVersion version = mock(WorkflowVersion.class);

        given(workflowCrudService.getWorkflowByOwner(userId, workflowId)).willReturn(workflow);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(workflowCrudService.loadDefinition(version)).willReturn(
            WorkflowDefinitionDocument.builder().nodes(nodes).edges(edges).build());

        return workflowService.getWorkflow(userId, workflowId);
    }

    /**
     * Mongo 배열에는 스키마가 없어 어떤 타입이든(문자열·숫자·배열·null) 담길 수 있다.
     * 이 DTO를 거치지 않는 저장 경로(ieum-agent 응답을 그대로 저장하는 {@code ChatService})가 넣은
     * 배열을 흉내내려면 타입을 못 박은 {@code List.of}로는 표현할 수 없어 raw 목록을 만든다.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rawList(Object... items) {
        return (List<Map<String, Object>>) (List<?>) Arrays.asList(items);
    }

    /** Mongo 문서에서 읽어온 노드 한 건을 흉내낸다. position은 null이면 키 자체를 넣지 않는다. */
    private Map<String, Object> node(String id, String type, String label,
            String description, Map<String, Object> position) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("label", label);
        node.put("description", description);
        if (position != null) {
            node.put("position", position);
        }
        node.put("config", Map.of("mappings", Map.of("k", "v")));
        return node;
    }

    /** Mongo 문서에서 읽어온 엣지 한 건. */
    private Map<String, Object> edge(Object source, Object target) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("source", source);
        edge.put("target", target);
        return edge;
    }

    @Test
    @DisplayName("저장된 label·description·position·config가 그대로 반환된다")
    void storedFields_areReturnedAsIs() {
        WorkflowResponse response = getWorkflowWithNodes(List.of(
            node("node-1", "AI", "문의 유형 나누기", "AI가 문의를 유형별로 나눠요.",
                Map.of("x", 420, "y", 120))));

        NodeView node = response.getNodes().get(0);
        assertThat(node.id()).isEqualTo("node-1");
        assertThat(node.type()).isEqualTo(NodeType.AI);
        assertThat(node.label()).isEqualTo("문의 유형 나누기");
        assertThat(node.description()).isEqualTo("AI가 문의를 유형별로 나눠요.");
        assertThat(node.position().x()).isEqualTo(420.0);
        assertThat(node.position().y()).isEqualTo(120.0);
        assertThat(node.config()).containsKey("mappings");
    }

    /**
     * 응답 타입을 요청 DTO에서 분리하면서 프론트 계약(필드명·중첩 구조)이 바뀌지 않았는지 본다.
     * 같은 JSON을 요청 DTO로 읽어 다시 직렬화한 것과 트리 비교한다.
     */
    @Test
    @DisplayName("NodeView·EdgeView의 응답 JSON 모양이 요청 DTO와 동일하다")
    void responseJson_hasSameShapeAsRequestDto() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String nodeJson = """
            { "id": "node-1", "type": "AI", "label": "분류",
              "description": "AI가 문의를 유형별로 나눠요.",
              "position": { "x": 420.0, "y": 120.0 },
              "config": { "llmProvider": "GEMINI" } }""";
        String edgeJson = """
            { "source": "node-1", "target": "node-2", "conditionType": "true" }""";

        Map<String, Object> rawNode =
            objectMapper.readValue(nodeJson, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> rawEdge =
            objectMapper.readValue(edgeJson, new TypeReference<Map<String, Object>>() {});

        NodeView nodeView = NodeView.fromDefinition(List.of(rawNode), workflowId).get(0);
        List<NodeView> nodes = List.of(nodeView,
            NodeView.fromDefinition(List.of(node("node-2", "AI", "l", "d",
                Map.of("x", 1, "y", 2))), workflowId).get(0));
        EdgeView edgeView = EdgeView.fromDefinition(List.of(rawEdge), nodes, workflowId).get(0);

        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(nodeView)))
            .isEqualTo(objectMapper.readTree(objectMapper.writeValueAsString(
                objectMapper.readValue(nodeJson, NodeDto.class))));
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(edgeView)))
            .isEqualTo(objectMapper.readTree(objectMapper.writeValueAsString(
                objectMapper.readValue(edgeJson, EdgeDto.class))));
    }

    @Test
    @DisplayName("좌표가 없는 노드에는 인덱스 기반 기본 좌표가 채워진다")
    void missingPosition_isFilledWithDefault() {
        WorkflowResponse response = getWorkflowWithNodes(List.of(
            node("node-1", "TRIGGER", "시작", "실행하면 시작해요.", null),
            node("node-2", "TRANSFORM", "가공", "받은 값을 가공해요.", null)));

        assertThat(response.getNodes().get(0).position().x()).isEqualTo(40.0);
        assertThat(response.getNodes().get(0).position().y()).isEqualTo(120.0);
        assertThat(response.getNodes().get(1).position().x()).isEqualTo(420.0);
        assertThat(response.getNodes().get(1).position().y()).isEqualTo(120.0);
    }

    /**
     * 저장 경로 검증. {@code WorkflowService.toJson}은 요청 DTO를 그대로 직렬화해 Mongo에 넣으므로,
     * DTO에 필드가 없으면 애초에 저장이 안 된다 — 이 이슈가 고친 유실 지점이다.
     */
    @Test
    @DisplayName("요청으로 받은 노드를 저장용 JSON으로 직렬화하면 description·position이 남는다")
    void serializedNode_keepsDescriptionAndPosition() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String request = """
            { "id": "node-1", "type": "AI", "label": "분류",
              "description": "AI가 문의를 유형별로 나눠요.",
              "position": { "x": 420, "y": 120 },
              "config": { "llmProvider": "GEMINI" } }""";

        NodeDto node = objectMapper.readValue(request, NodeDto.class);
        String stored = objectMapper.writeValueAsString(node);

        assertThat(objectMapper.readTree(stored).get("description").asText())
            .isEqualTo("AI가 문의를 유형별로 나눠요.");
        assertThat(objectMapper.readTree(stored).at("/position/x").asDouble()).isEqualTo(420.0);
        assertThat(objectMapper.readTree(stored).at("/position/y").asDouble()).isEqualTo(120.0);
        assertThat(objectMapper.readTree(stored).get("type").asText()).isEqualTo("AI");
    }

    @Test
    @DisplayName("좌표가 있는 노드는 기본값으로 덮이지 않는다")
    void existingPosition_isNotOverwritten() {
        WorkflowResponse response = getWorkflowWithNodes(List.of(
            node("node-1", "TRIGGER", "시작", "실행하면 시작해요.", null),
            node("node-2", "TRANSFORM", "가공", "받은 값을 가공해요.",
                Map.of("x", 999, "y", 777))));

        assertThat(response.getNodes().get(1).position().x()).isEqualTo(999.0);
        assertThat(response.getNodes().get(1).position().y()).isEqualTo(777.0);
    }

    @Test
    @DisplayName("한쪽 좌표만 있는 노드는 빠진 축만 기본값으로 채워진다")
    void partialPosition_isFilledPerAxis() {
        WorkflowResponse response = getWorkflowWithNodes(List.of(
            node("node-1", "TRIGGER", "시작", "실행하면 시작해요.", Map.of("x", 999)),
            node("node-2", "TRANSFORM", "가공", "받은 값을 가공해요.", Map.of("y", 777))));

        // x만 있던 노드 — y만 채워지고 x는 보존된다.
        assertThat(response.getNodes().get(0).position().x()).isEqualTo(999.0);
        assertThat(response.getNodes().get(0).position().y()).isEqualTo(120.0);
        // y만 있던 노드 — x는 인덱스 기반 기본값(40 + 1*380), y는 보존된다.
        assertThat(response.getNodes().get(1).position().x()).isEqualTo(420.0);
        assertThat(response.getNodes().get(1).position().y()).isEqualTo(777.0);
    }

    /**
     * 조회 경로의 원본인 Mongo {@code workflow_definitions.nodes}에는 요청 DTO를 거치지 않고
     * 저장되는 경로(ieum-agent 생성분)가 있어 BE가 {@code type} 값을 통제하지 못한다.
     * 어긋난 문서 하나가 조회 전체를 500으로 무너뜨리면 안 된다.
     */
    @Test
    @DisplayName("enum에 없는 type이 섞여 있어도 상세 조회는 200이고 정상 노드는 온전하다")
    void unknownNodeType_doesNotBreakDetailRead() {
        WorkflowResponse response = getWorkflowWithNodes(List.of(
            node("node-x", "SLACK", "슬랙", "슬랙으로 보내요.", Map.of("x", 40, "y", 120)),
            node("node-2", "AI", "분류", "AI가 문의를 유형별로 나눠요.", Map.of("x", 420, "y", 120))));

        assertThat(response.getNodes()).hasSize(2);
        assertThat(response.getNodes().get(0).type()).isNull();
        assertThat(response.getNodes().get(0).id()).isEqualTo("node-x");
        assertThat(response.getNodes().get(0).label()).isEqualTo("슬랙");
        assertThat(response.getNodes().get(0).position().x()).isEqualTo(40.0);

        NodeView intact = response.getNodes().get(1);
        assertThat(intact.type()).isEqualTo(NodeType.AI);
        assertThat(intact.label()).isEqualTo("분류");
        assertThat(intact.description()).isEqualTo("AI가 문의를 유형별로 나눠요.");
        assertThat(intact.config()).containsKey("mappings");
    }

    /** 목록 조회는 사용자의 모든 워크플로우를 한 번에 변환하므로, 한 건이 어긋나면 페이지 전체가 죽는다. */
    @Test
    @DisplayName("enum에 없는 type이 섞인 워크플로우가 있어도 목록 조회가 죽지 않는다")
    void unknownNodeType_doesNotBreakListRead() {
        PageResponse<WorkflowResponse> page = listOf(
            List.of(node("node-x", "SLACK", "슬랙", "슬랙으로 보내요.", Map.of("x", 40, "y", 120))),
            List.of());

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getNodes().get(0).type()).isNull();
        assertThat(page.getContent().get(1).getNodes().get(0).type()).isEqualTo(NodeType.TRIGGER);
    }

    /**
     * 노드 목록 자체가 없는 정의 — 껍데기만 만들어진 워크플로우에서 나온다.
     * 매핑이 null을 그대로 흘리면 뒤따르는 처리가 NPE로 터져 조회가 500이 됐었다.
     */
    @Test
    @DisplayName("정의의 nodes·edges가 null이어도 조회는 성공하고 빈 목록이 반환된다")
    void nullNodes_returnEmptyLists() {
        Workflow workflow = mock(Workflow.class);
        WorkflowVersion version = mock(WorkflowVersion.class);
        given(workflowCrudService.getWorkflowByOwner(userId, workflowId)).willReturn(workflow);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(workflowCrudService.loadDefinition(version))
            .willReturn(WorkflowDefinitionDocument.builder().build());

        WorkflowResponse response = workflowService.getWorkflow(userId, workflowId);

        assertThat(response.getNodes()).isEmpty();
        assertThat(response.getEdges()).isEmpty();
    }

    /**
     * {@code position}·{@code config}가 객체가 아닌 노드. 검증을 거치지 않는 저장 경로에서만 나올 수
     * 있다. 노드를 버리는 대신 축별 기본 좌표와 빈 config로 채워 렌더 가능한 상태로 돌려준다 —
     * id가 있으면 엣지가 가리킬 수 있는 노드라 빼면 그래프가 더 크게 깨진다.
     */
    @Test
    @DisplayName("position·config가 객체가 아니어도 노드는 기본 좌표·빈 config로 남는다")
    void malformedPositionAndConfig_fallBackToDefaults() {
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("id", "node-broken");
        broken.put("type", "AI");
        broken.put("label", "깨진 노드");
        broken.put("description", "구조가 어긋난 노드예요.");
        broken.put("position", "40,120");
        broken.put("config", "not-an-object");

        WorkflowResponse response = getWorkflowWithNodes(List.of(
            broken,
            node("node-ok", "AI", "분류", "AI가 문의를 유형별로 나눠요.", Map.of("x", 420, "y", 120))));

        assertThat(response.getNodes()).hasSize(2);
        NodeView fixed = response.getNodes().get(0);
        assertThat(fixed.position().x()).isEqualTo(40.0);
        assertThat(fixed.position().y()).isEqualTo(120.0);
        assertThat(fixed.config()).isEmpty();
        assertThat(response.getNodes().get(1).position().x()).isEqualTo(420.0);
    }

    /** {@code x}가 숫자가 아닌 경우도 축 단위로만 기본값이 들어간다. */
    @Test
    @DisplayName("좌표 값이 숫자가 아니면 그 축만 기본값으로 채워진다")
    void nonNumericCoordinate_isFilledPerAxis() {
        WorkflowResponse response = getWorkflowWithNodes(List.of(
            node("node-1", "AI", "분류", "설명이에요.", Map.of("x", Map.of("nested", 1), "y", 777))));

        assertThat(response.getNodes().get(0).position().x()).isEqualTo(40.0);
        assertThat(response.getNodes().get(0).position().y()).isEqualTo(777.0);
    }

    /**
     * 배열 원소가 null이거나 객체가 아닌 경우(문자열·숫자·배열). Mongo 배열에는 스키마가 없어
     * 가능한 상태다.
     */
    @Test
    @DisplayName("nodes 배열에 null·비객체 원소가 섞여 있어도 조회는 200이고 정상 노드는 온전하다")
    void nullOrNonObjectNodeElement_isDroppedAndOtherNodesSurvive() {
        WorkflowResponse response = getWorkflowWithNodes(rawList(
            null,
            "node-1",
            42,
            List.of("node-2"),
            node("node-ok", "AI", "분류", "AI가 문의를 유형별로 나눠요.", Map.of("x", 420, "y", 120))));

        assertThat(response.getNodes()).hasSize(1);
        assertThat(response.getNodes().get(0).id()).isEqualTo("node-ok");
        assertThat(response.getNodes().get(0).type()).isEqualTo(NodeType.AI);
    }

    /**
     * {@code id}가 없는 노드는 엣지가 가리킬 수도 프론트가 식별할 수도 없어 버린다. 남겨 두면 응답의
     * 노드 id 집합에 null이 섞여, null을 {@code source}로 가진 엣지가 끊긴 엣지 정리를 통과했다.
     */
    @Test
    @DisplayName("id가 없거나 null인 노드는 제외되고, 그 노드를 가리키던 엣지도 남지 않는다")
    void nodeWithoutId_isDroppedAndItsEdgesToo() {
        Map<String, Object> noIdKey = new LinkedHashMap<>();
        noIdKey.put("type", "AI");
        noIdKey.put("label", "id 없는 노드");

        WorkflowResponse response = getWorkflow(
            List.of(
                noIdKey,
                node(null, "AI", "id가 null인 노드", "설명이에요.", Map.of("x", 40, "y", 120)),
                node("node-1", "TRIGGER", "시작", "실행하면 시작해요.", Map.of("x", 40, "y", 120)),
                node("node-2", "AI", "분류", "AI가 문의를 유형별로 나눠요.", Map.of("x", 420, "y", 120))),
            rawList(
                edge(null, "node-1"),
                edge("node-1", null),
                edge("node-1", "node-2")));

        assertThat(response.getNodes()).extracting(NodeView::id)
            .containsExactly("node-1", "node-2");
        assertThat(response.getEdges()).hasSize(1);
        assertThat(response.getEdges().get(0).source()).isEqualTo("node-1");
        assertThat(response.getEdges().get(0).target()).isEqualTo("node-2");
    }

    /** 빈 객체는 id가 없으므로 노드가 아니다 — 필드가 전부 null인 노드로 응답에 남으면 안 된다. */
    @Test
    @DisplayName("빈 객체 원소는 노드로 통과하지 않는다")
    void emptyObjectNode_isDropped() {
        WorkflowResponse response = getWorkflowWithNodes(List.of(
            Map.of(),
            node("node-ok", "AI", "분류", "AI가 문의를 유형별로 나눠요.", Map.of("x", 420, "y", 120))));

        assertThat(response.getNodes()).extracting(NodeView::id).containsExactly("node-ok");
    }

    /** 목록 조회는 모든 워크플로우를 한 번에 변환하므로, 어긋난 원소 하나가 페이지 전체를 죽인다. */
    @Test
    @DisplayName("null·비객체·id 없는 원소가 섞인 워크플로우가 있어도 목록 조회가 죽지 않는다")
    void malformedElements_doNotBreakListRead() {
        PageResponse<WorkflowResponse> page = listOf(
            rawList(null, "node-1", Map.of()),
            rawList(null, "edge-1", edge(null, null)));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getNodes()).isEmpty();
        assertThat(page.getContent().get(0).getEdges()).isEmpty();
        assertThat(page.getContent().get(1).getNodes().get(0).type()).isEqualTo(NodeType.TRIGGER);
    }

    /** 엣지도 항목 단위로 걸러져야 나머지 연결이 살아남는다. */
    @Test
    @DisplayName("구조가 어긋난 엣지가 섞여 있어도 조회는 200이고 정상 엣지는 온전하다")
    void malformedEdge_isDroppedAndValidEdgeSurvives() {
        WorkflowResponse response = getWorkflow(
            List.of(
                node("node-1", "TRIGGER", "시작", "실행하면 시작해요.", Map.of("x", 40, "y", 120)),
                node("node-2", "AI", "분류", "AI가 문의를 유형별로 나눠요.", Map.of("x", 420, "y", 120))),
            rawList(
                null,
                "node-1->node-2",
                edge(Map.of("id", "node-1"), "node-2"),
                edge("", "node-2"),
                edge("node-1", "node-2")));

        assertThat(response.getNodes()).hasSize(2);
        assertThat(response.getEdges()).hasSize(1);
        assertThat(response.getEdges().get(0).source()).isEqualTo("node-1");
        assertThat(response.getEdges().get(0).target()).isEqualTo("node-2");
    }

    /**
     * 노드가 버려지거나 애초에 없던 id를 가리키는 엣지는 응답 안에 대상이 없는 참조가 된다.
     * 서버가 "응답의 엣지는 응답의 노드만 가리킨다"를 지켜야 프론트가 매번 방어하지 않는다.
     */
    @Test
    @DisplayName("응답에 없는 노드를 가리키는 엣지는 제외되고 나머지 연결은 남는다")
    void danglingEdge_isDropped() {
        WorkflowResponse response = getWorkflow(
            rawList(
                node("node-1", "TRIGGER", "시작", "실행하면 시작해요.", Map.of("x", 40, "y", 120)),
                "버려질 원소",
                node("node-2", "AI", "분류", "AI가 문의를 유형별로 나눠요.", Map.of("x", 420, "y", 120))),
            List.of(
                edge("node-1", "node-ghost"),
                edge("node-ghost", "node-2"),
                edge("node-1", "node-2")));

        assertThat(response.getNodes()).hasSize(2);
        assertThat(response.getEdges()).hasSize(1);
        assertThat(response.getEdges().get(0).source()).isEqualTo("node-1");
        assertThat(response.getEdges().get(0).target()).isEqualTo("node-2");
    }

    /**
     * 알 수 없는 {@code type}은 노드를 남기므로 그 노드를 가리키는 엣지도 남아야 한다.
     * 같은 응답에서 없는 노드를 가리키는 엣지는 빠지는지도 함께 본다 — 끊긴 엣지 정리를 통째로
     * 없애도 통과하는 테스트가 되지 않게.
     */
    @Test
    @DisplayName("enum에 없는 type 노드의 엣지는 유지되고, 없는 노드를 가리키는 엣지만 빠진다")
    void unknownNodeType_keepsItsEdges() {
        WorkflowResponse response = getWorkflow(
            List.of(
                node("node-x", "SLACK", "슬랙", "슬랙으로 보내요.", Map.of("x", 40, "y", 120)),
                node("node-2", "AI", "분류", "AI가 문의를 유형별로 나눠요.", Map.of("x", 420, "y", 120))),
            List.of(
                edge("node-x", "node-2"),
                edge("node-2", "node-ghost")));

        assertThat(response.getNodes()).hasSize(2);
        assertThat(response.getEdges()).hasSize(1);
        assertThat(response.getEdges().get(0).source()).isEqualTo("node-x");
        assertThat(response.getEdges().get(0).target()).isEqualTo("node-2");
    }

    /**
     * 어긋난 정의를 가진 워크플로우 하나와 정상 워크플로우 하나로 목록 조회를 돌린다.
     * 정상 워크플로우는 TRIGGER 노드 한 건짜리다.
     */
    private PageResponse<WorkflowResponse> listOf(List<Map<String, Object>> brokenNodes,
            List<Map<String, Object>> brokenEdges) {
        Workflow broken = mock(Workflow.class);
        Workflow healthy = mock(Workflow.class);
        UUID brokenId = UUID.randomUUID();
        UUID healthyId = UUID.randomUUID();
        given(broken.getId()).willReturn(brokenId);
        given(healthy.getId()).willReturn(healthyId);

        WorkflowVersion brokenVersion = mock(WorkflowVersion.class);
        WorkflowVersion healthyVersion = mock(WorkflowVersion.class);

        given(workflowCrudService.listWorkflows(userId, 0, 20))
            .willReturn(List.of(broken, healthy));
        given(workflowCrudService.hasNextWorkflows(userId, 0, 20)).willReturn(false);
        given(workflowCrudService.getLatestVersionMap(List.of(brokenId, healthyId)))
            .willReturn(Map.of(brokenId, brokenVersion, healthyId, healthyVersion));
        given(workflowCrudService.loadDefinition(brokenVersion)).willReturn(
            WorkflowDefinitionDocument.builder().nodes(brokenNodes).edges(brokenEdges).build());
        given(workflowCrudService.loadDefinition(healthyVersion)).willReturn(
            WorkflowDefinitionDocument.builder()
                .nodes(List.of(node("node-1", "TRIGGER", "시작", "실행하면 시작해요.",
                    Map.of("x", 40, "y", 120))))
                .edges(List.of()).build());

        return workflowService.getWorkflows(userId, null, 20);
    }
}
