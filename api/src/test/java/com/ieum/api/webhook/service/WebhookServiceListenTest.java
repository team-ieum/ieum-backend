package com.ieum.api.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ieum.api.webhook.dto.WebhookTriggerRequest;
import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.service.NodeTestRunner;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import com.ieum.workflowcore.service.WorkflowWebhookTokenService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * webhook 수신 — listen(노드 테스트 대기) 중이면 첫 1건이 샘플이 된다. listen이 없으면 기존 동작이
 * 그대로여야 하므로 회귀 케이스도 함께 고정한다(이 서비스엔 단위 테스트가 없었다).
 */
class WebhookServiceListenTest {

    private final WorkflowCrudService crudService = mock(WorkflowCrudService.class);
    private final WorkflowExecutionService executionService = mock(WorkflowExecutionService.class);
    private final WorkflowExecutionRunner runner = mock(WorkflowExecutionRunner.class);
    private final WebhookListenStore listenStore = mock(WebhookListenStore.class);
    private final NodeTestRunner nodeTestRunner = mock(NodeTestRunner.class);
    private final WorkflowWebhookTokenService tokenService = mock(WorkflowWebhookTokenService.class);
    private final WebhookRateLimiter rateLimiter = mock(WebhookRateLimiter.class);
    private final WebhookService service = new WebhookService(
        crudService, executionService, runner, listenStore, nodeTestRunner, tokenService, rateLimiter);

    private final UUID workflowId = UUID.randomUUID();
    private final WorkflowVersion version = mock(WorkflowVersion.class);
    private final Map<String, Object> payload = Map.of("orderId", "ORD-1");
    private final WebhookTriggerRequest request = request(payload);
    private final HookRequest hook = new HookRequest("POST", Map.of("authorization", "***"), Map.of(), payload,
        "{\"orderId\":\"ORD-1\"}".getBytes(StandardCharsets.UTF_8));

    private static WebhookTriggerRequest request(Map<String, Object> payload) {
        WebhookTriggerRequest request = new WebhookTriggerRequest();
        ReflectionTestUtils.setField(request, "payload", payload);
        return request;
    }

    private Workflow workflow(boolean active, TriggerType type) {
        Workflow workflow = Workflow.builder().userId(UUID.randomUUID()).name("w")
            .isActive(true).triggerType(type).build();
        if (!active) {
            workflow.deactivate();
        }
        ReflectionTestUtils.setField(workflow, "id", workflowId);
        given(crudService.getWorkflowById(workflowId)).willReturn(workflow);
        given(crudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(executionService.prepareExecution(eq(workflow), eq(version), eq(TriggerType.WEBHOOK), any()))
            .willReturn(WorkflowExecution.builder().workflow(workflow).workflowVersion(version)
                .status(ExecutionStatus.PENDING).triggerType(TriggerType.WEBHOOK)
                .startedAt(LocalDateTime.now()).build());
        return workflow;
    }

    private void listening(String nodeId) {
        given(listenStore.consume(workflowId)).willReturn(Optional.ofNullable(nodeId));
    }

    // ──────────────────── listen 없음 — 기존 동작 회귀 ────────────────────

    @Test
    @DisplayName("listen 없음 + 활성 WEBHOOK → 기존대로 실행, 샘플 기록 없음")
    void withoutListener_activeWebhook_runsAsBefore() {
        Workflow workflow = workflow(true, TriggerType.WEBHOOK);
        listening(null);

        WorkflowExecutionResponse response = service.trigger(workflowId, request);

        assertThat(response).isNotNull();
        verify(runner).run(eq(version), any(), eq(payload));
        verifyNoInteractions(nodeTestRunner);
    }

    @Test
    @DisplayName("listen 없음 + 비활성 → 기존대로 WORKFLOW_NOT_ACTIVE, listen 없음 + MANUAL → WEBHOOK_TRIGGER_MISMATCH")
    void withoutListener_rejectionsUnchanged() {
        workflow(false, TriggerType.WEBHOOK);
        listening(null);
        assertThatThrownBy(() -> service.trigger(workflowId, request))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_NOT_ACTIVE));

        workflow(true, TriggerType.MANUAL);
        assertThatThrownBy(() -> service.trigger(workflowId, request))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBHOOK_TRIGGER_MISMATCH));
        verify(runner, never()).run(any(), any(), any());
        verifyNoInteractions(nodeTestRunner);
    }

    // ──────────────────── listen 중 ────────────────────

    @Test
    @DisplayName("listen + 비활성 → 샘플만 저장하고 null(202), 실행 없음")
    void listening_inactive_savesSampleOnly() {
        Workflow workflow = workflow(false, TriggerType.WEBHOOK);
        listening("t");

        WorkflowExecutionResponse response = service.trigger(workflowId, request);

        assertThat(response).isNull();
        verify(nodeTestRunner).recordWebhookSample(workflow, "t", payload);
        verify(executionService, never()).prepareExecution(any(), any(), any(), any());
        verify(runner, never()).run(any(), any(), any());
    }

    @Test
    @DisplayName("listen + triggerType 불일치(MANUAL) → 샘플만 저장하고 null(202), 실행 없음")
    void listening_triggerMismatch_savesSampleOnly() {
        Workflow workflow = workflow(true, TriggerType.MANUAL);
        listening("t");

        assertThat(service.trigger(workflowId, request)).isNull();

        verify(nodeTestRunner).recordWebhookSample(workflow, "t", payload);
        verify(runner, never()).run(any(), any(), any());
    }

    @Test
    @DisplayName("listen + 활성 WEBHOOK → 샘플 저장 + 기존 실행")
    void listening_activeWebhook_savesSampleAndRuns() {
        Workflow workflow = workflow(true, TriggerType.WEBHOOK);
        listening("t");

        WorkflowExecutionResponse response = service.trigger(workflowId, request);

        assertThat(response).isNotNull();
        verify(nodeTestRunner).recordWebhookSample(workflow, "t", payload);
        verify(runner).run(eq(version), any(), eq(payload));
    }

    @Test
    @DisplayName("[Review Focus 2] 동시 두 번째 웹훅은 샘플이 아니다 — 소비는 한 번뿐이라 두 번째는 평범한 웹훅(비활성이면 기존 400)")
    void secondConcurrentWebhookIsNotSampled() {
        Workflow workflow = workflow(false, TriggerType.WEBHOOK);
        given(listenStore.consume(workflowId)).willReturn(Optional.of("t"), Optional.empty());

        assertThat(service.trigger(workflowId, request)).isNull();
        assertThatThrownBy(() -> service.trigger(workflowId, request))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_NOT_ACTIVE));

        verify(nodeTestRunner, times(1)).recordWebhookSample(eq(workflow), eq("t"), any());
    }

    @Test
    @DisplayName("본문이 없으면 빈 페이로드로 샘플을 만든다")
    void nullBodyBecomesEmptyPayload() {
        Workflow workflow = workflow(false, TriggerType.WEBHOOK);
        listening("t");

        service.trigger(workflowId, null);

        verify(nodeTestRunner).recordWebhookSample(workflow, "t", Map.of());
    }

    @Test
    @DisplayName("샘플 저장이 실패해도 활성 WEBHOOK 실행은 막지 않는다 / 비활성이면 기존 400")
    void sampleFailureDoesNotBlockExecution() {
        Workflow workflow = workflow(true, TriggerType.WEBHOOK);
        listening("t");
        given(nodeTestRunner.recordWebhookSample(eq(workflow), eq("t"), any()))
            .willThrow(new IllegalStateException("mongo down"));

        assertThat(service.trigger(workflowId, request)).isNotNull();
        verify(runner).run(eq(version), any(), eq(payload));

        Workflow inactive = workflow(false, TriggerType.WEBHOOK);
        given(nodeTestRunner.recordWebhookSample(eq(inactive), eq("t"), any()))
            .willThrow(new IllegalStateException("mongo down"));
        assertThatThrownBy(() -> service.trigger(workflowId, request))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_NOT_ACTIVE));
    }

    // ──────────────────── 토큰 주소(/api/v1/hooks/{token}) ────────────────────

    @Test
    @DisplayName("토큰 주소 — 토큰이 맞지 않으면 404, 횟수를 세지 않고 워크플로우 조회·listen 소비 없음")
    void resolveHook_unknownToken_404() {
        given(tokenService.findWorkflowIdByToken("bad")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveHook("bad"))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_NOT_FOUND));
        verifyNoInteractions(rateLimiter, crudService, listenStore, runner);
    }

    @Test
    @DisplayName("토큰 주소 — 한도 안이면 workflowId, 넘으면 429 WEBHOOK_RATE_LIMITED")
    void resolveHook_rateLimit() {
        given(tokenService.findWorkflowIdByToken("tok")).willReturn(Optional.of(workflowId));
        given(rateLimiter.tryAcquire(workflowId)).willReturn(true, false);

        assertThat(service.resolveHook("tok")).isEqualTo(workflowId);
        assertThatThrownBy(() -> service.resolveHook("tok"))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBHOOK_RATE_LIMITED));
        verifyNoInteractions(crudService, listenStore, runner);
    }

    @Test
    @DisplayName("기존 주소(/api/v1/webhooks/{workflowId})엔 횟수 제한이 걸리지 않는다(R1)")
    void trigger_notRateLimited() {
        workflow(true, TriggerType.WEBHOOK);
        listening(null);

        for (int i = 0; i < 61; i++) {
            service.trigger(workflowId, request);
        }
        verify(runner, times(61)).run(eq(version), any(), eq(payload));
        verifyNoInteractions(rateLimiter);
    }

    @Test
    @DisplayName("토큰 주소 — 기존 주소와 같은 흐름: 활성 WEBHOOK면 payload 그대로 실행")
    void receive_runsWithPayload() {
        workflow(true, TriggerType.WEBHOOK);
        listening(null);

        assertThat(service.receive(workflowId, hook)).isNotNull();
        verify(runner).run(eq(version), any(), eq(payload));
    }

    @Test
    @DisplayName("토큰 주소 — listen 중이면 샘플 저장, 비활성이면 샘플만 남기고 null")
    void receive_listening_savesSample() {
        Workflow workflow = workflow(false, TriggerType.WEBHOOK);
        listening("t");

        assertThat(service.receive(workflowId, hook)).isNull();
        verify(nodeTestRunner).recordWebhookSample(workflow, "t", payload);
        verify(runner, never()).run(any(), any(), any());
    }

    @Test
    @DisplayName("토큰 주소 — 비활성·불일치 거절은 기존 주소와 같다")
    void receive_rejectionsSameAsTrigger() {
        workflow(false, TriggerType.WEBHOOK);
        listening(null);
        assertThatThrownBy(() -> service.receive(workflowId, hook))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_NOT_ACTIVE));

        workflow(true, TriggerType.MANUAL);
        assertThatThrownBy(() -> service.receive(workflowId, hook))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBHOOK_TRIGGER_MISMATCH));
    }

    // ──────────────────── includeRaw(R5·R6·R8) ────────────────────

    private void savedNodes(List<Map<String, Object>> nodes) {
        given(crudService.loadDefinition(version))
            .willReturn(WorkflowDefinitionDocument.builder().nodes(nodes).build());
    }

    private static Map<String, Object> trigger(Map<String, Object> config) {
        Map<String, Object> node = new java.util.HashMap<>(Map.of("id", "t", "type", "TRIGGER"));
        if (config != null) {
            node.put("config", config);
        }
        return node;
    }

    @Test
    @DisplayName("includeRaw true → 실행·샘플 둘 다 {method, headers, query, body, rawBody}")
    void receive_includeRaw_runsAndSamplesRawShape() {
        Workflow workflow = workflow(true, TriggerType.WEBHOOK);
        listening("t");
        savedNodes(List.of(Map.of("id", "a", "type", "AI"), trigger(Map.of("includeRaw", true))));

        service.receive(workflowId, hook);

        Map<String, Object> raw = hook.output(true);
        assertThat(raw).containsEntry("body", payload).containsEntry("rawBody", "{\"orderId\":\"ORD-1\"}")
            .containsEntry("method", "POST").containsEntry("headers", Map.of("authorization", "***"));
        verify(nodeTestRunner).recordWebhookSample(workflow, "t", raw);
        verify(runner).run(eq(version), any(), eq(raw));
    }

    @Test
    @DisplayName("includeRaw false·config 없음·문자열 \"true\"·트리거 노드 없음 → R4 모양 그대로")
    void receive_includeRawAbsent_keepsPlainShape() {
        List<List<Map<String, Object>>> cases = List.of(
            List.of(trigger(Map.of("includeRaw", false))),
            List.of(trigger(null)),
            List.of(trigger(Map.of("includeRaw", "true"))),
            List.of());
        for (List<Map<String, Object>> nodes : cases) {
            workflow(true, TriggerType.WEBHOOK);
            listening(null);
            savedNodes(nodes);
            service.receive(workflowId, hook);
        }
        verify(runner, times(cases.size())).run(eq(version), any(), eq(payload));
    }

    @Test
    @DisplayName("버전 없음 → includeRaw false로 보고 R4 모양으로 샘플 저장(listen + 비활성이라 실행 없이 null)")
    void receive_noVersion_plainShapeSample() {
        Workflow workflow = workflow(false, TriggerType.WEBHOOK);
        given(crudService.findLatestVersion(workflowId)).willReturn(Optional.empty());
        listening("t");

        assertThat(service.receive(workflowId, hook)).isNull();

        verify(nodeTestRunner).recordWebhookSample(workflow, "t", payload);
        verify(crudService, never()).loadDefinition(any());
    }
}
