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
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.service.NodeTestRunner;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.LocalDateTime;
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
    private final WebhookService service =
        new WebhookService(crudService, executionService, runner, listenStore, nodeTestRunner);

    private final UUID workflowId = UUID.randomUUID();
    private final WorkflowVersion version = mock(WorkflowVersion.class);
    private final Map<String, Object> payload = Map.of("orderId", "ORD-1");
    private final WebhookTriggerRequest request = request(payload);

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
}
