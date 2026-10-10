package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.credential.domain.Credential;
import com.ieum.api.credential.service.CredentialService;
import com.ieum.api.webhook.service.WebhookListenStore;
import com.ieum.api.workflow.dto.NodeTestRequest;
import com.ieum.api.workflow.dto.NodeTestResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.SampleStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.service.NodeTestResult;
import com.ieum.workflowcore.service.NodeTestRunner;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NodeTestServiceTest {

    private final WorkflowCrudService crudService = mock(WorkflowCrudService.class);
    private final NodeTestRunner runner = mock(NodeTestRunner.class);
    private final CredentialService credentialService = mock(CredentialService.class);
    private final WebhookListenStore listenStore = mock(WebhookListenStore.class);
    private final NodeTestService service =
        new NodeTestService(crudService, runner, credentialService, listenStore);
    private final ObjectMapper json = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    private Workflow workflow(TriggerType triggerType) {
        return Workflow.builder().userId(userId).name("w").isActive(true).triggerType(triggerType).build();
    }

    private NodeTestRequest request(String body) throws Exception {
        return json.readValue(body, NodeTestRequest.class);
    }

    private void owns(Workflow workflow) {
        given(crudService.getWorkflowByOwner(userId, workflowId)).willReturn(workflow);
    }

    private static void assertErrorCode(Throwable thrown, ErrorCode expected) {
        assertThat(thrown).isInstanceOfSatisfying(CustomException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    // ──────────────────── 소유자·가드·경로 ────────────────────

    @Test
    @DisplayName("[Review Focus 1] 남의 workflowId는 무엇보다 먼저 404 — 러너·listen·크레덴셜 조회 없음 (테스트·샘플 조회 모두)")
    void foreignWorkflowIsRejectedBeforeAnythingElse() throws Exception {
        given(crudService.getWorkflowByOwner(userId, workflowId))
            .willThrow(new CustomException(ErrorCode.WORKFLOW_NOT_FOUND));

        assertErrorCode(catchThrowable(() -> service.test(userId, workflowId, "node-1", request("{}"))),
            ErrorCode.WORKFLOW_NOT_FOUND);
        assertErrorCode(catchThrowable(() -> service.getSample(userId, workflowId, "node-1")),
            ErrorCode.WORKFLOW_NOT_FOUND);

        verify(crudService, org.mockito.Mockito.times(2)).getWorkflowByOwner(userId, workflowId);
        verifyNoInteractions(runner, listenStore, credentialService);
    }

    private static Throwable catchThrowable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        return org.assertj.core.api.Assertions.catchThrowable(call);
    }

    @Test
    @DisplayName("body의 node.id가 경로 nodeId와 다르면 400 — 실행 없음")
    void bodyNodeIdMismatchIs400() throws Exception {
        owns(workflow(TriggerType.MANUAL));

        assertThatThrownBy(() -> service.test(userId, workflowId, "node-1", request(
            "{\"node\":{\"id\":\"other\",\"type\":\"HTTP\",\"label\":\"x\",\"config\":{}}}")))
            .satisfies(t -> assertErrorCode(t, ErrorCode.INVALID_INPUT));
        verify(runner, never()).run(any(), any(), any());
    }

    @Test
    @DisplayName("body 노드의 남의 credentialId는 저장 경로와 같은 가드로 400(INVALID_WORKFLOW)")
    void foreignCredentialInBodyNodeIsRejected() throws Exception {
        owns(workflow(TriggerType.MANUAL));
        given(credentialService.getByUserId(userId)).willReturn(
            List.of(Credential.builder().id(UUID.randomUUID()).userId(userId).build()));
        String foreign = UUID.randomUUID().toString();

        assertThatThrownBy(() -> service.test(userId, workflowId, "node-1", request(
            "{\"node\":{\"id\":\"node-1\",\"type\":\"AI\",\"label\":\"x\","
                + "\"config\":{\"credentialId\":\"" + foreign + "\"}}}")))
            .satisfies(t -> assertErrorCode(t, ErrorCode.INVALID_WORKFLOW));
        verify(runner, never()).run(any(), any(), any());
    }

    @Test
    @DisplayName("body 노드의 웹훅 URL 원문은 400")
    void rawWebhookUrlInBodyNodeIsRejected() throws Exception {
        owns(workflow(TriggerType.MANUAL));
        given(credentialService.getByUserId(userId)).willReturn(List.of());

        assertThatThrownBy(() -> service.test(userId, workflowId, "node-1", request(
            "{\"node\":{\"id\":\"node-1\",\"type\":\"HTTP\",\"label\":\"x\","
                + "\"config\":{\"url\":\"https://hooks.slack.com/services/T0/B0/xyz\"}}}")))
            .satisfies(t -> assertErrorCode(t, ErrorCode.INVALID_WORKFLOW));
        verify(runner, never()).run(any(), any(), any());
    }

    @Test
    @DisplayName("body 노드의 API 키 원문은 400")
    void inlineSecretInBodyNodeIsRejected() throws Exception {
        owns(workflow(TriggerType.MANUAL));
        given(credentialService.getByUserId(userId)).willReturn(List.of());

        assertThatThrownBy(() -> service.test(userId, workflowId, "node-1", request(
            "{\"node\":{\"id\":\"node-1\",\"type\":\"AI\",\"label\":\"x\",\"config\":{\"apiKey\":\"sk-123\"}}}")))
            .satisfies(t -> assertErrorCode(t, ErrorCode.INVALID_WORKFLOW));
        verify(runner, never()).run(any(), any(), any());
    }

    // ──────────────────── 노드 선택·실행 ────────────────────

    @Test
    @DisplayName("body 노드가 있으면 저장된 노드를 읽지 않고 그 정의로 실행한다")
    void bodyNodeIsUsedInsteadOfSavedOne() throws Exception {
        Workflow workflow = workflow(TriggerType.MANUAL);
        owns(workflow);
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(runner.run(eq(workflow), any(Node.class), any())).willReturn(
            new NodeTestResult(SampleStatus.SUCCESS, Map.of("ok", true), null, LocalDateTime.now()));

        NodeTestResponse response = service.test(userId, workflowId, "node-1", request(
            "{\"node\":{\"id\":\"node-1\",\"type\":\"HTTP\",\"label\":\"호출\",\"config\":{\"method\":\"GET\"}}}"));

        ArgumentCaptor<Node> node = ArgumentCaptor.forClass(Node.class);
        verify(runner).run(eq(workflow), node.capture(), any());
        assertThat(node.getValue().getId()).isEqualTo("node-1");
        assertThat(node.getValue().getType()).isEqualTo(NodeType.HTTP);
        assertThat(node.getValue().getConfig()).isEqualTo(Map.of("method", "GET"));
        verify(runner, never()).findSavedNode(any(), any());
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getOutput()).isEqualTo(Map.of("ok", true));
    }

    @Test
    @DisplayName("body가 없으면 저장된 최신 버전의 노드를 쓰고, 없으면 404")
    void savedNodeIsUsedWithoutBody() throws Exception {
        Workflow workflow = workflow(TriggerType.MANUAL);
        owns(workflow);
        Node saved = new Node("node-1", NodeType.TRANSFORM, "변환", new HashMap<>());
        given(runner.findSavedNode(workflowId, "node-1")).willReturn(Optional.of(saved));
        given(runner.run(eq(workflow), eq(saved), any())).willReturn(
            new NodeTestResult(SampleStatus.FAILED, null, "boom", LocalDateTime.now()));

        NodeTestResponse failed = service.test(userId, workflowId, "node-1", request("{}"));

        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getError()).isEqualTo("boom");
        assertThat(failed.getOutput()).isNull();

        given(runner.findSavedNode(workflowId, "ghost")).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.test(userId, workflowId, "ghost", request("{}")))
            .satisfies(t -> assertErrorCode(t, ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("manual 트리거는 요청 input을 러너에 넘긴다")
    void manualTriggerPassesInput() throws Exception {
        Workflow workflow = workflow(TriggerType.MANUAL);
        owns(workflow);
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(runner.run(eq(workflow), any(Node.class), any())).willReturn(
            new NodeTestResult(SampleStatus.SUCCESS, Map.of(), null, LocalDateTime.now()));

        service.test(userId, workflowId, "t", request(
            "{\"node\":{\"id\":\"t\",\"type\":\"TRIGGER\",\"label\":\"트리거\","
                + "\"config\":{\"triggerType\":\"MANUAL\"}},\"input\":{\"score\":5}}"));

        verify(runner).run(eq(workflow), any(Node.class), eq(Map.of("score", 5)));
        verifyNoInteractions(listenStore);
    }

    @Test
    @DisplayName("webhook 트리거는 실행하지 않고 대기를 시작한다 — LISTENING + 경로 + 만료 시각")
    void webhookTriggerStartsListening() throws Exception {
        owns(workflow(TriggerType.MANUAL));
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);
        given(listenStore.start(workflowId, "t")).willReturn(expiresAt);

        NodeTestResponse response = service.test(userId, workflowId, "t", request(
            "{\"node\":{\"id\":\"t\",\"type\":\"TRIGGER\",\"label\":\"수신\","
                + "\"config\":{\"triggerType\":\"WEBHOOK\"}}}"));

        assertThat(response.getStatus()).isEqualTo("LISTENING");
        assertThat(response.getWebhookUrl()).isEqualTo("/api/v1/webhooks/" + workflowId);
        assertThat(response.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(response.getOutput()).isNull();
        verify(runner, never()).run(any(), any(), any());
    }

    @Test
    @DisplayName("노드 config에 triggerType이 없으면 워크플로우의 triggerType을 따른다")
    void triggerWithoutTypeFollowsWorkflowTriggerType() throws Exception {
        Workflow webhookWorkflow = workflow(TriggerType.WEBHOOK);
        owns(webhookWorkflow);
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(listenStore.start(workflowId, "t")).willReturn(LocalDateTime.now().plusMinutes(5));
        String body = "{\"node\":{\"id\":\"t\",\"type\":\"TRIGGER\",\"label\":\"수신\",\"config\":{}}}";

        assertThat(service.test(userId, workflowId, "t", request(body)).getStatus()).isEqualTo("LISTENING");

        Workflow manualWorkflow = workflow(TriggerType.MANUAL);
        owns(manualWorkflow);
        given(runner.run(eq(manualWorkflow), any(Node.class), any())).willReturn(
            new NodeTestResult(SampleStatus.SUCCESS, Map.of(), null, LocalDateTime.now()));
        assertThat(service.test(userId, workflowId, "t", request(body)).getStatus()).isEqualTo("SUCCESS");
    }

    // ──────────────────── 샘플 조회 ────────────────────

    @Test
    @DisplayName("GET sample — 있으면 최신 샘플, 없으면 404 TEST_SAMPLE_NOT_FOUND")
    void getSampleFoundAndNotFound() {
        owns(workflow(TriggerType.MANUAL));
        LocalDateTime testedAt = LocalDateTime.of(2026, 10, 6, 12, 0);
        given(runner.findSample(workflowId, "node-1")).willReturn(
            Optional.of(new NodeTestResult(SampleStatus.SUCCESS, Map.of("n", 1), null, testedAt)));
        given(runner.findSample(workflowId, "none")).willReturn(Optional.empty());

        NodeTestResponse found = service.getSample(userId, workflowId, "node-1");

        assertThat(found.getStatus()).isEqualTo("SUCCESS");
        assertThat(found.getOutput()).isEqualTo(Map.of("n", 1));
        assertThat(found.getTestedAt()).isEqualTo(testedAt);
        assertThatThrownBy(() -> service.getSample(userId, workflowId, "none"))
            .satisfies(t -> assertErrorCode(t, ErrorCode.TEST_SAMPLE_NOT_FOUND));
    }
}
