package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.dto.NodeDto;
import com.ieum.api.workflow.dto.WorkflowResponse;
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
}
