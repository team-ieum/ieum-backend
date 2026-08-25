package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.credential.service.CredentialService;
import com.ieum.api.webhookcredential.service.WebhookCredentialService;
import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.dto.CreateWorkflowRequest;
import com.ieum.api.workflow.dto.UpdateWorkflowRequest;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 저장되는 엣지에 id가 붙는지 본다 (IEUM-BE-65).
 *
 * <p>엣지 id는 <b>요청 필수 필드가 아니다</b> — ieum-agent가 만든 엣지에는 id가 없어 필수로 두면
 * 채팅으로 만든 워크플로우의 저장·수정이 전부 막힌다. 대신 서버가 저장 직전에 채운다. 그래서
 * 여기서 고정하는 계약은 두 가지다 — ① 요청이 보낸 id는 그대로 저장된다 ② 없으면 서버가 만든다.
 *
 * <p>id가 필요한 이유는 같은 {@code (source, target)}을 공유하는 형제 엣지를 구분하기 위해서다.
 * 짝짓기 규칙 자체는 {@link EdgeDefinitionMerger}가 들고 있다.
 */
class WorkflowEdgeIdTest {

    private final WorkflowCrudService workflowCrudService = mock(WorkflowCrudService.class);
    private final WorkflowService workflowService = new WorkflowService(
        workflowCrudService,
        mock(WorkflowExecutionService.class),
        mock(WorkflowExecutionRunner.class),
        mock(ExecutionEventPublisher.class),
        new ObjectMapper(),
        mock(WebhookCredentialService.class),
        mock(CredentialService.class));

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    /** CONDITION 두 분기가 한 노드로 합류하는 모양 — {@code (source, target)}만으로는 구분되지 않는다. */
    private static final String SIBLING_EDGES = """
        { "source": "node-if", "target": "node-join", "conditionType": "true" },
        { "source": "node-if", "target": "node-join", "conditionType": "false" }""";

    private <T> T request(Class<T> type, String edgesJson) {
        String body = """
            {
              "name": "엣지 워크플로우",
              "nodes": [
                { "id": "node-if", "type": "CONDITION", "label": "조건" },
                { "id": "node-join", "type": "AI", "label": "요약하기" }
              ],
              "edges": [ %s ]
            }""".formatted(edgesJson);
        try {
            return jsonMapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final Workflow workflow = mock(Workflow.class);
    private final WorkflowVersion version = mock(WorkflowVersion.class);

    /** 응답 변환까지 가려면 crud 반환값이 필요하다. */
    private void stubSaveSucceeds() {
        given(version.getWorkflow()).willReturn(workflow);
        given(workflowCrudService.getWorkflowByOwner(userId, workflowId)).willReturn(workflow);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.empty());
        given(workflowCrudService.createWorkflow(
            any(), anyString(), any(), anyString(), anyString(), any(), any()))
            .willReturn(version);
        given(workflowCrudService.updateWorkflow(
            any(), any(), anyString(), any(), anyString(), anyString(), any(), any()))
            .willReturn(version);
        given(workflowCrudService.loadDefinition(version)).willReturn(
            WorkflowDefinitionDocument.builder().nodes(List.of()).edges(List.of()).build());
    }

    /** 직전 버전 정의가 있는 수정 경로 — 이 엣지들이 병합의 베이스가 된다. */
    private void stubPreviousEdges(List<Map<String, Object>> previousEdges) {
        stubSaveSucceeds();
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(workflowCrudService.loadDefinition(version)).willReturn(
            WorkflowDefinitionDocument.builder().nodes(List.of()).edges(previousEdges).build());
    }

    private static Map<String, Object> previousEdge(String id, String conditionType) {
        return Map.of("id", id, "source", "node-if", "target", "node-join",
            "conditionType", conditionType);
    }

    /** 생성 저장 경로로 넘어간 edgesJson을 파싱해 돌려준다. */
    private List<Map<String, Object>> createdEdges() {
        ArgumentCaptor<String> edgesJson = ArgumentCaptor.forClass(String.class);
        verify(workflowCrudService).createWorkflow(eq(userId), any(), any(), any(),
            edgesJson.capture(), any(), any());
        return parse(edgesJson.getValue());
    }

    /** 수정 저장 경로로 넘어간 edgesJson을 파싱해 돌려준다. */
    private List<Map<String, Object>> updatedEdges() {
        ArgumentCaptor<String> edgesJson = ArgumentCaptor.forClass(String.class);
        verify(workflowCrudService).updateWorkflow(eq(userId), eq(workflowId), any(), any(),
            any(), edgesJson.capture(), any(), any());
        return parse(edgesJson.getValue());
    }

    private List<Map<String, Object>> parse(String json) {
        try {
            return jsonMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("생성 요청의 엣지에 id가 없으면 서버가 채워 저장한다")
    void create_fillsMissingEdgeId() {
        stubSaveSucceeds();

        workflowService.createWorkflow(userId, request(CreateWorkflowRequest.class, SIBLING_EDGES));

        List<Map<String, Object>> saved = createdEdges();
        assertThat(saved).hasSize(2);
        assertThat(saved).allSatisfy(edge ->
            assertThat((String) edge.get("id")).isNotBlank());
        // 형제 엣지를 구분하는 것이 목적이라 서로 달라야 한다.
        assertThat(saved).extracting(edge -> edge.get("id")).doesNotHaveDuplicates();
        // 채운 id가 다른 필드를 밀어내지 않는다.
        assertThat(saved.get(0).get("conditionType")).isEqualTo("true");
        assertThat(saved.get(1).get("conditionType")).isEqualTo("false");
    }

    @Test
    @DisplayName("생성 요청이 보낸 엣지 id는 그대로 저장된다")
    void create_keepsRequestedEdgeId() {
        stubSaveSucceeds();

        workflowService.createWorkflow(userId, request(CreateWorkflowRequest.class, """
            { "id": "edge-true", "source": "node-if", "target": "node-join",
              "conditionType": "true" },
            { "source": "node-if", "target": "node-join", "conditionType": "false" }"""));

        List<Map<String, Object>> saved = createdEdges();
        assertThat(saved.get(0).get("id")).isEqualTo("edge-true");
        // 섞여 있어도 빈 자리만 서버가 채운다.
        assertThat((String) saved.get(1).get("id")).isNotBlank().isNotEqualTo("edge-true");
    }

    /** 수정은 생성과 다른 저장 지점이라(병합 결과를 저장한다) 배선을 따로 못 박는다. */
    @Test
    @DisplayName("수정 요청의 엣지 id도 보낸 것은 유지되고 빈 것은 서버가 채운다")
    void update_keepsRequestedIdAndFillsMissing() {
        stubSaveSucceeds();

        workflowService.updateWorkflow(userId, workflowId, request(UpdateWorkflowRequest.class, """
            { "id": "edge-true", "source": "node-if", "target": "node-join",
              "conditionType": "true" },
            { "id": "   ", "source": "node-if", "target": "node-join",
              "conditionType": "false" }"""));

        List<Map<String, Object>> saved = updatedEdges();
        assertThat(saved.get(0).get("id")).isEqualTo("edge-true");
        // 공백뿐인 id는 없는 것으로 본다 — 짝짓기 키가 될 수 없다.
        assertThat((String) saved.get(1).get("id")).isNotBlank().isNotEqualTo("   ");
    }

    /**
     * 왕복 안정성의 핵심 — 요청이 id를 생략해도 저장되는 id는 이전 것 그대로여야 한다. 새 UUID를
     * 찍으면 GET이 돌려준 id가 다음 PUT마다 전량 교체돼 "엣지 식별자"가 회차마다 갈리는 nonce가
     * 된다(FE가 id를 되돌려 보내야만 안정적인 계약은 안정적인 것이 아니다).
     */
    @Test
    @DisplayName("요청이 id를 생략해도 짝지어진 이전 엣지의 id가 그대로 저장된다")
    void update_keepsPreviousEdgeIdWhenRequestOmitsIt() {
        stubPreviousEdges(List.of(
            previousEdge("edge-A", "true"),
            previousEdge("edge-B", "false")));

        workflowService.updateWorkflow(userId, workflowId,
            request(UpdateWorkflowRequest.class, SIBLING_EDGES));

        assertThat(updatedEdges()).extracting(edge -> edge.get("id"))
            .containsExactly("edge-A", "edge-B");
    }
}
