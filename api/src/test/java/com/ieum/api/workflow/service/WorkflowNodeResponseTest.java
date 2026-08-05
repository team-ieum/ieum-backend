package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.dto.NodeDto;
import com.ieum.api.workflow.dto.WorkflowResponse;
import com.ieum.common.dto.PageResponse;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상세 조회 응답의 노드 계약을 검증한다 (IEUM-BE-60).
 *
 * <p>저장된 정의를 그대로 되돌려주는지, 좌표가 없는 노드에 기본 배치값이 채워지는지를 본다.
 * 직렬화 동작 자체가 검증 대상이므로 {@link ObjectMapper}는 mock이 아닌 실물을 쓴다.
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
        Workflow workflow = mock(Workflow.class);
        WorkflowVersion version = mock(WorkflowVersion.class);

        given(workflowCrudService.getWorkflowByOwner(userId, workflowId)).willReturn(workflow);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(workflowCrudService.loadDefinition(version)).willReturn(
            WorkflowDefinitionDocument.builder().nodes(nodes).edges(List.of()).build());

        return workflowService.getWorkflow(userId, workflowId);
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

    /**
     * 구조가 어긋난 노드. {@code position}이 객체가 아니거나 {@code x}/{@code y}가 숫자로 변환되지
     * 않는 경우로, 검증을 거치지 않는 저장 경로(ieum-agent 생성분)에서만 나올 수 있다.
     */
    private Map<String, Object> malformedNode(String id, Object position) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "AI");
        node.put("label", "깨진 노드");
        node.put("description", "구조가 어긋난 노드예요.");
        node.put("position", position);
        return node;
    }

    @Test
    @DisplayName("저장된 label·description·position·config가 그대로 반환된다")
    void storedFields_areReturnedAsIs() {
        WorkflowResponse response = getWorkflowWithNodes(List.of(
            node("node-1", "AI", "문의 유형 나누기", "AI가 문의를 유형별로 나눠요.",
                Map.of("x", 420, "y", 120))));

        NodeDto node = response.getNodes().get(0);
        assertThat(node.getId()).isEqualTo("node-1");
        assertThat(node.getType()).isEqualTo(NodeType.AI);
        assertThat(node.getLabel()).isEqualTo("문의 유형 나누기");
        assertThat(node.getDescription()).isEqualTo("AI가 문의를 유형별로 나눠요.");
        assertThat(node.getPosition().getX()).isEqualTo(420.0);
        assertThat(node.getPosition().getY()).isEqualTo(120.0);
        assertThat(node.getConfig()).containsKey("mappings");
    }

    @Test
    @DisplayName("좌표가 없는 노드에는 인덱스 기반 기본 좌표가 채워진다")
    void missingPosition_isFilledWithDefault() {
        WorkflowResponse response = getWorkflowWithNodes(List.of(
            node("node-1", "TRIGGER", "시작", "실행하면 시작해요.", null),
            node("node-2", "TRANSFORM", "가공", "받은 값을 가공해요.", null)));

        assertThat(response.getNodes().get(0).getPosition().getX()).isEqualTo(40.0);
        assertThat(response.getNodes().get(0).getPosition().getY()).isEqualTo(120.0);
        assertThat(response.getNodes().get(1).getPosition().getX()).isEqualTo(420.0);
        assertThat(response.getNodes().get(1).getPosition().getY()).isEqualTo(120.0);
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

        assertThat(response.getNodes().get(1).getPosition().getX()).isEqualTo(999.0);
        assertThat(response.getNodes().get(1).getPosition().getY()).isEqualTo(777.0);
    }

    @Test
    @DisplayName("한쪽 좌표만 있는 노드는 빠진 축만 기본값으로 채워진다")
    void partialPosition_isFilledPerAxis() {
        WorkflowResponse response = getWorkflowWithNodes(List.of(
            node("node-1", "TRIGGER", "시작", "실행하면 시작해요.", Map.of("x", 999)),
            node("node-2", "TRANSFORM", "가공", "받은 값을 가공해요.", Map.of("y", 777))));

        // x만 있던 노드 — y만 채워지고 x는 보존된다.
        assertThat(response.getNodes().get(0).getPosition().getX()).isEqualTo(999.0);
        assertThat(response.getNodes().get(0).getPosition().getY()).isEqualTo(120.0);
        // y만 있던 노드 — x는 인덱스 기반 기본값(40 + 1*380), y는 보존된다.
        assertThat(response.getNodes().get(1).getPosition().getX()).isEqualTo(420.0);
        assertThat(response.getNodes().get(1).getPosition().getY()).isEqualTo(777.0);
    }

    /**
     * 조회 경로의 원본인 Mongo {@code workflow_definitions.nodes}에는 이 DTO를 거치지 않고
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
        assertThat(response.getNodes().get(0).getType()).isNull();
        assertThat(response.getNodes().get(0).getId()).isEqualTo("node-x");
        assertThat(response.getNodes().get(0).getLabel()).isEqualTo("슬랙");
        assertThat(response.getNodes().get(0).getPosition().getX()).isEqualTo(40.0);

        NodeDto intact = response.getNodes().get(1);
        assertThat(intact.getType()).isEqualTo(NodeType.AI);
        assertThat(intact.getLabel()).isEqualTo("분류");
        assertThat(intact.getDescription()).isEqualTo("AI가 문의를 유형별로 나눠요.");
        assertThat(intact.getConfig()).containsKey("mappings");
    }

    /** 목록 조회는 사용자의 모든 워크플로우를 한 번에 변환하므로, 한 건이 어긋나면 페이지 전체가 죽는다. */
    @Test
    @DisplayName("enum에 없는 type이 섞인 워크플로우가 있어도 목록 조회가 죽지 않는다")
    void unknownNodeType_doesNotBreakListRead() {
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
            WorkflowDefinitionDocument.builder()
                .nodes(List.of(node("node-x", "SLACK", "슬랙", "슬랙으로 보내요.",
                    Map.of("x", 40, "y", 120))))
                .edges(List.of()).build());
        given(workflowCrudService.loadDefinition(healthyVersion)).willReturn(
            WorkflowDefinitionDocument.builder()
                .nodes(List.of(node("node-1", "TRIGGER", "시작", "실행하면 시작해요.",
                    Map.of("x", 40, "y", 120))))
                .edges(List.of()).build());

        PageResponse<WorkflowResponse> page = workflowService.getWorkflows(userId, null, 20);

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getNodes().get(0).getType()).isNull();
        assertThat(page.getContent().get(1).getNodes().get(0).getType()).isEqualTo(NodeType.TRIGGER);
    }

    /**
     * 노드 목록 자체가 없는 정의 — 껍데기만 만들어진 워크플로우에서 나온다.
     * 변환기가 null을 그대로 흘리면 뒤따르는 기본 좌표 채우기가 NPE로 터져 조회가 500이 됐었다.
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
     * 알 수 없는 {@code type}과 달리 구조가 어긋난 노드는 DTO로 만들 수 없어 응답에서 빠진다.
     * 대신 같은 워크플로우의 정상 노드는 살아남아야 한다 — 노드 하나 때문에 캔버스가 통째로
     * 비면 사용자가 무엇이 깨졌는지 알 수도, 나머지를 복구할 수도 없다.
     */
    @Test
    @DisplayName("position 구조가 어긋난 노드가 섞여 있어도 상세 조회는 200이고 정상 노드는 온전하다")
    void malformedPosition_doesNotBreakDetailRead() {
        WorkflowResponse response = getWorkflowWithNodes(List.of(
            malformedNode("node-broken-1", "40,120"),
            malformedNode("node-broken-2", Map.of("x", Map.of("nested", 1), "y", 120)),
            node("node-ok", "AI", "분류", "AI가 문의를 유형별로 나눠요.", Map.of("x", 420, "y", 120))));

        assertThat(response.getNodes()).hasSize(1);
        NodeDto intact = response.getNodes().get(0);
        assertThat(intact.getId()).isEqualTo("node-ok");
        assertThat(intact.getType()).isEqualTo(NodeType.AI);
        assertThat(intact.getPosition().getX()).isEqualTo(420.0);
        assertThat(intact.getPosition().getY()).isEqualTo(120.0);
    }

    /** 목록 조회는 모든 워크플로우를 한 번에 변환하므로, 한 건이 어긋나면 페이지 전체가 죽는다. */
    @Test
    @DisplayName("position 구조가 어긋난 워크플로우가 있어도 목록 조회가 죽지 않는다")
    void malformedPosition_doesNotBreakListRead() {
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
            WorkflowDefinitionDocument.builder()
                .nodes(List.of(malformedNode("node-broken", "40,120")))
                .edges(List.of()).build());
        given(workflowCrudService.loadDefinition(healthyVersion)).willReturn(
            WorkflowDefinitionDocument.builder()
                .nodes(List.of(node("node-1", "TRIGGER", "시작", "실행하면 시작해요.",
                    Map.of("x", 40, "y", 120))))
                .edges(List.of()).build());

        PageResponse<WorkflowResponse> page = workflowService.getWorkflows(userId, null, 20);

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getNodes()).isEmpty();
        assertThat(page.getContent().get(1).getNodes().get(0).getType()).isEqualTo(NodeType.TRIGGER);
    }
}
