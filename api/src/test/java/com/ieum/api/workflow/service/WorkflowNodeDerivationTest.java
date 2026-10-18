package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.credential.service.CredentialService;
import com.ieum.api.webhookcredential.service.WebhookCredentialService;
import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.dto.CreateWorkflowRequest;
import com.ieum.api.workflow.dto.UpdateWorkflowRequest;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.catalog.NodeDerivation;
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

/** REST 생성·수정은 NodeDerivation을 거친 Map을 저장한다 (IEUM-BE-92). */
class WorkflowNodeDerivationTest {

    private static final EventCatalog CATALOG = EventCatalog.loadFromClasspath();

    private final WorkflowCrudService workflowCrudService = mock(WorkflowCrudService.class);
    private final WorkflowService workflowService = new WorkflowService(
        workflowCrudService,
        mock(WorkflowExecutionService.class),
        mock(WorkflowExecutionRunner.class),
        mock(ExecutionEventPublisher.class),
        new ObjectMapper(),
        mock(WebhookCredentialService.class),
        mock(CredentialService.class),
        new NodeDerivation(CATALOG),
        CATALOG);

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    private static String brandOf(String event) {
        return CATALOG.app(CATALOG.event(event).orElseThrow().app()).orElseThrow().brand();
    }

    private static String typeOf(String event) {
        return CATALOG.event(event).orElseThrow().nodeType().name();
    }

    private void stubSaveSucceeds() {
        Workflow workflow = mock(Workflow.class);
        WorkflowVersion version = mock(WorkflowVersion.class);
        given(version.getWorkflow()).willReturn(workflow);
        given(workflowCrudService.getWorkflowByOwner(userId, workflowId)).willReturn(workflow);
        given(workflowCrudService.createWorkflow(
            any(), anyString(), any(), anyString(), anyString(), any(), any())).willReturn(version);
        given(workflowCrudService.updateWorkflow(
            any(), any(), anyString(), any(), anyString(), anyString(), any(), any())).willReturn(version);
        given(workflowCrudService.loadDefinition(version)).willReturn(
            WorkflowDefinitionDocument.builder().nodes(List.of()).edges(List.of()).build());
    }

    private void stubPreviousNodes(List<Map<String, Object>> nodes) {
        WorkflowVersion previous = mock(WorkflowVersion.class);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(previous));
        given(workflowCrudService.loadDefinition(previous)).willReturn(
            WorkflowDefinitionDocument.builder().nodes(nodes).edges(List.of()).build());
    }

    private <T> T read(Class<T> type, String nodesJson) {
        String body = """
            { "name": "파생 워크플로우", "nodes": [ %s ], "edges": [] }""".formatted(nodesJson);
        try {
            return jsonMapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> createdNodes() throws Exception {
        ArgumentCaptor<String> nodesJson = ArgumentCaptor.forClass(String.class);
        verify(workflowCrudService).createWorkflow(eq(userId), anyString(), any(), nodesJson.capture(),
            anyString(), any(), any());
        return jsonMapper.readValue(nodesJson.getValue(), new TypeReference<>() {});
    }

    private List<Map<String, Object>> updatedNodes() throws Exception {
        ArgumentCaptor<String> nodesJson = ArgumentCaptor.forClass(String.class);
        verify(workflowCrudService).updateWorkflow(eq(userId), eq(workflowId), anyString(), any(),
            nodesJson.capture(), anyString(), any(), any());
        return jsonMapper.readValue(nodesJson.getValue(), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> config(Map<String, Object> node) {
        return (Map<String, Object>) node.get("config");
    }

    @Test
    @DisplayName("생성: 저장되는 노드는 event로 계산한 Map이다 — 요청 type 무시, 비AI brand 덮음, 옛 키 삭제")
    void create_storesDerivedNodes() throws Exception {
        stubSaveSucceeds();

        workflowService.createWorkflow(userId, read(CreateWorkflowRequest.class, """
            { "id": "n1", "event": "github.create_issue", "type": "HTTP", "label": "이슈 만들기",
              "description": "이슈를 만들어요.", "position": { "x": 40, "y": 120 },
              "config": { "owner": "ieum", "repo": "demo", "brand": "evil", "serviceType": "GITHUB",
                          "triggerType": "MANUAL", "tools": [ { "name": "builtin:github_create_issue" } ] } }"""));

        Map<String, Object> saved = createdNodes().get(0);
        assertThat(saved).containsEntry("event", "github.create_issue")
            .containsEntry("type", typeOf("github.create_issue"))
            .containsEntry("description", "이슈를 만들어요.");
        assertThat(config(saved)).containsOnly(
            entry("owner", "ieum"), entry("repo", "demo"), entry("brand", brandOf("github.create_issue")));
    }

    @Test
    @DisplayName("생성: 모르는 event면 400이고 저장하지 않는다 — 메시지에 event·노드 id")
    void create_unknownEvent_isRejected() {
        CreateWorkflowRequest request = read(CreateWorkflowRequest.class, """
            { "id": "n1", "event": "github.delete_repo", "label": "x", "config": {} }""");

        assertThatThrownBy(() -> workflowService.createWorkflow(userId, request))
            .isInstanceOfSatisfying(CustomException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW);
                assertThat(e.getMessage()).contains("github.delete_repo").contains("n1");
            });
        verify(workflowCrudService, never()).createWorkflow(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("수정: 병합 결과에 파생이 돈다 — AI 노드는 이전 brand를 잇고, 비AI 노드는 카탈로그 brand")
    void update_derivesMergedNodes() throws Exception {
        stubSaveSucceeds();
        stubPreviousNodes(List.of(Map.of("id", "n-ai", "event", "ai.agent", "type", "AI", "label", "요약",
            "config", Map.of("brand", "notion", "tools", List.of(Map.of("name", "builtin:notion_search"))))));

        workflowService.updateWorkflow(userId, workflowId, read(UpdateWorkflowRequest.class, """
            { "id": "n-ai", "event": "ai.agent", "label": "요약",
              "config": { "tools": [ { "name": "builtin:notion_search" } ] } },
            { "id": "n-slack", "event": "slack.send_message", "type": "AI", "label": "알림",
              "config": { "brand": "evil", "text": "hi" } }"""));

        List<Map<String, Object>> saved = updatedNodes();
        assertThat(saved.get(0)).containsEntry("type", "AI");
        assertThat(config(saved.get(0))).containsEntry("brand", "notion");
        assertThat(saved.get(1)).containsEntry("event", "slack.send_message")
            .containsEntry("type", typeOf("slack.send_message"));
        assertThat(config(saved.get(1))).containsEntry("brand", brandOf("slack.send_message"))
            .containsEntry("text", "hi");
    }
}
