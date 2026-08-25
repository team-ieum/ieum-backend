package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.credential.service.CredentialService;
import com.ieum.api.webhookcredential.service.WebhookCredentialService;
import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.dto.UpdateWorkflowRequest;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
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
import org.springframework.http.HttpStatus;

/**
 * 수정 요청이 보내지 않은 노드 필드가 직전 버전에서 살아남는지, 그리고 그 병합 결과가 저장 전에
 * 가드를 거치는지 본다 (IEUM-BE-65 Task 2 — 배선 검증).
 *
 * <p>병합 규칙 자체는 {@link NodeDefinitionMergerTest}가 본다. 여기서 고정하는 계약은 세 가지다 —
 * ① 이전 정의를 실제로 읽어 병합한 결과가 저장된다 ② 가드가 요청이 아니라 병합 결과에 돈다
 * ③ 소유권 검증이 이전 정의 조회보다 먼저 온다.
 */
class WorkflowPartialUpdateTest {

    private final WorkflowCrudService workflowCrudService = mock(WorkflowCrudService.class);
    private final CredentialService credentialService = mock(CredentialService.class);
    private final WorkflowService workflowService = new WorkflowService(
        workflowCrudService,
        mock(WorkflowExecutionService.class),
        mock(WorkflowExecutionRunner.class),
        mock(ExecutionEventPublisher.class),
        new ObjectMapper(),
        mock(WebhookCredentialService.class),
        credentialService);

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    private static final String NODE_ID = "node-ai";

    /** 직전 버전 정의를 스텁한다. {@code null}이면 버전 자체가 없는 워크플로우다. */
    private void stubPreviousNodes(List<Map<String, Object>> previousNodes) {
        if (previousNodes == null) {
            given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.empty());
            return;
        }
        WorkflowVersion previousVersion = mock(WorkflowVersion.class);
        given(workflowCrudService.findLatestVersion(workflowId))
            .willReturn(Optional.of(previousVersion));
        given(workflowCrudService.loadDefinition(previousVersion)).willReturn(
            WorkflowDefinitionDocument.builder().nodes(previousNodes).edges(List.of()).build());
    }

    /** 저장이 성공하는 경로. 응답 변환까지 가려면 crud 반환값이 필요하다. */
    private void stubSaveSucceeds() {
        Workflow workflow = mock(Workflow.class);
        WorkflowVersion savedVersion = mock(WorkflowVersion.class);
        given(savedVersion.getWorkflow()).willReturn(workflow);
        given(workflowCrudService.updateWorkflow(
            any(), any(), any(), any(), any(), any(), any(), any())).willReturn(savedVersion);
        given(workflowCrudService.loadDefinition(savedVersion)).willReturn(
            WorkflowDefinitionDocument.builder().nodes(List.of()).edges(List.of()).build());
    }

    private UpdateWorkflowRequest request(String nodeJson) {
        String body = """
            {
              "name": "수정된 워크플로우",
              "nodes": [ %s ],
              "edges": []
            }""".formatted(nodeJson);
        try {
            return jsonMapper.readValue(body, UpdateWorkflowRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 저장 경로로 넘어간 nodesJson을 파싱해 돌려준다. */
    private List<Map<String, Object>> capturedNodes() {
        ArgumentCaptor<String> nodesJson = ArgumentCaptor.forClass(String.class);
        verify(workflowCrudService).updateWorkflow(eq(userId), eq(workflowId), any(), any(),
            nodesJson.capture(), any(), any(), any());
        try {
            return jsonMapper.readValue(nodesJson.getValue(),
                new TypeReference<List<Map<String, Object>>>() {
                });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("요청이 생략한 description·position은 직전 버전 값으로 저장된다")
    void keepsOmittedFieldsFromPreviousVersion() {
        stubPreviousNodes(List.of(Map.of(
            "id", NODE_ID,
            "type", "AI",
            "label", "요약하기",
            "description", "글을 요약해요.",
            "position", Map.of("x", 40, "y", 120),
            "config", Map.of("model", "gemini-3.5-flash"))));
        stubSaveSucceeds();

        // FE가 보내는 최소 요청 — description·position이 없다.
        workflowService.updateWorkflow(userId, workflowId, request("""
            { "id": "%s", "type": "AI", "label": "요약하기 v2",
              "config": { "model": "gemini-3.5-flash" } }""".formatted(NODE_ID)));

        Map<String, Object> saved = capturedNodes().get(0);
        assertThat(saved.get("description")).isEqualTo("글을 요약해요.");
        assertThat(saved.get("position")).isEqualTo(Map.of("x", 40, "y", 120));
        // 요청이 보낸 값은 이전 값을 덮어쓴다.
        assertThat(saved.get("label")).isEqualTo("요약하기 v2");
    }

    @Test
    @DisplayName("요청이 config를 생략해 이전 config가 되살아나면, 그 config에도 가드가 돈다")
    void guardsRunOnRevivedPreviousConfig() {
        UUID foreignCredentialId = UUID.randomUUID();
        stubPreviousNodes(List.of(Map.of(
            "id", NODE_ID,
            "type", "AI",
            "label", "요약하기",
            "config", Map.of("credentialId", foreignCredentialId.toString()))));
        given(credentialService.getByUserId(userId)).willReturn(List.of());

        // config 없는 요청 — 요청만 검사하면 아무것도 걸리지 않는다.
        UpdateWorkflowRequest request = request(
            "{ \"id\": \"" + NODE_ID + "\", \"type\": \"AI\", \"label\": \"요약하기\" }");

        assertThatThrownBy(() -> workflowService.updateWorkflow(userId, workflowId, request))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_WORKFLOW))
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode().getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageNotContaining(foreignCredentialId.toString());

        // 거부됐으면 새 버전이 남지 않아야 한다.
        verify(workflowCrudService, never()).updateWorkflow(
            any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("최신 버전이 없는 워크플로우는 예외 없이 요청 노드 그대로 저장한다")
    void savesRequestNodesWhenNoPreviousVersion() {
        stubPreviousNodes(null);
        stubSaveSucceeds();

        assertThatCode(() -> workflowService.updateWorkflow(userId, workflowId, request("""
            { "id": "%s", "type": "AI", "label": "요약하기" }""".formatted(NODE_ID))))
            .doesNotThrowAnyException();

        Map<String, Object> saved = capturedNodes().get(0);
        assertThat(saved).containsOnlyKeys("id", "type", "label");
        assertThat(saved.get("id")).isEqualTo(NODE_ID);
    }

    @Test
    @DisplayName("소유권 검증이 이전 정의 조회보다 먼저다 — 남의 정의는 읽지도 않는다")
    void verifiesOwnershipBeforeReadingPreviousDefinition() {
        willThrow(new CustomException(ErrorCode.WORKFLOW_NOT_FOUND))
            .given(workflowCrudService).getWorkflowByOwner(userId, workflowId);

        UpdateWorkflowRequest request = request(
            "{ \"id\": \"" + NODE_ID + "\", \"type\": \"AI\", \"label\": \"요약하기\" }");

        assertThatThrownBy(() -> workflowService.updateWorkflow(userId, workflowId, request))
            .isInstanceOf(CustomException.class);

        verify(workflowCrudService, never()).findLatestVersion(any());
        verify(workflowCrudService, never()).loadDefinition(any());
    }
}
