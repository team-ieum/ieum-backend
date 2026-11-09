package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionDelayServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T01:30:00Z");

    @Mock private WorkflowExecutionService workflowExecutionService;
    @Mock private ExecutionRetryService executionRetryService;
    @Mock private WorkflowCrudService workflowCrudService;
    @InjectMocks private ExecutionDelayService service;

    private final UUID executionId = UUID.randomUUID();
    private final UUID continuationId = UUID.randomUUID();
    private WorkflowExecution original;
    private Workflow workflow;

    @BeforeEach
    void setUp() {
        original = mock(WorkflowExecution.class);
        workflow = mock(Workflow.class);
        lenient().when(workflowExecutionService.lockExecutionWithVersion(executionId)).thenReturn(original);
        lenient().when(original.getStatus()).thenReturn(ExecutionStatus.WAITING_DELAY);
        lenient().when(original.getResumeAt()).thenReturn(local(NOW.minus(Duration.ofMinutes(1))));
        lenient().when(original.getWorkflow()).thenReturn(workflow);
        lenient().when(workflow.isActive()).thenReturn(true);
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private void verifyNoWrites() {
        verify(workflowExecutionService, never()).recordDelayRelease(any(), any());
        verify(workflowExecutionService, never()).markAsFailed(any(), any(), any());
        verify(original, never()).complete();
        verify(executionRetryService, never()).startContinuation(any(), any());
    }

    @Test
    @DisplayName("resume_재개시각_지나면_DELAY_해제_후_이어진실행")
    void resume_afterResumeAt_releasesDelayAndStartsContinuation() {
        WorkflowExecution continuation = mock(WorkflowExecution.class);
        given(continuation.getId()).willReturn(continuationId);
        given(executionRetryService.startContinuation(workflow, original)).willReturn(continuation);

        Optional<UUID> result = service.resume(executionId, NOW);

        InOrder order = inOrder(workflowExecutionService, original, executionRetryService);
        order.verify(workflowExecutionService).recordDelayRelease(original, NOW);
        order.verify(original).complete();
        order.verify(executionRetryService).startContinuation(workflow, original);
        assertThat(result).contains(continuationId);
    }

    @Test
    @DisplayName("resume_비활성이면_실패_알림_경로")
    void resume_inactiveWorkflow_failsWithAlert() {
        given(workflow.isActive()).willReturn(false);

        Optional<UUID> result = service.resume(executionId, NOW);

        verify(workflowExecutionService).markAsFailed(
            executionId, "워크플로우가 꺼져 있어 재개하지 않았어요", ExecutionStatus.WAITING_DELAY);
        verify(workflowExecutionService, never()).recordDelayRelease(any(), any());
        verify(executionRetryService, never()).startContinuation(any(), any());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("resume_이미_취소된_실행은_건드리지_않는다")
    void resume_alreadyFailed_doesNothing() {
        given(original.getStatus()).willReturn(ExecutionStatus.FAILED);

        assertThat(service.resume(executionId, NOW)).isEmpty();
        verifyNoWrites();
    }

    @Test
    @DisplayName("resume_재개시각_전이면_no_op")
    void resume_beforeResumeAt_doesNothing() {
        given(original.getResumeAt()).willReturn(local(NOW.plus(Duration.ofMinutes(1))));

        assertThat(service.resume(executionId, NOW)).isEmpty();
        verifyNoWrites();
    }

    @Test
    @DisplayName("resume_재개시각_없으면_no_op")
    void resume_withoutResumeAt_doesNothing() {
        given(original.getResumeAt()).willReturn(null);

        assertThat(service.resume(executionId, NOW)).isEmpty();
        verifyNoWrites();
    }

    // ──────────────────────────── 취소 ────────────────────────────

    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    private void ownedBy(UUID owner) {
        given(workflow.getId()).willReturn(workflowId);
        lenient().when(original.getWorkflowVersion()).thenReturn(mock(WorkflowVersion.class));
        lenient().when(workflowCrudService.getWorkflowByOwner(owner, workflowId)).thenReturn(workflow);
    }

    @Test
    @DisplayName("cancel_대기면_FAILED_사유_알림없음")
    void cancel_waitingDelay_failsWithReasonWithoutAlert() {
        ownedBy(userId);
        doAnswer(i -> given(original.getStatus()).willReturn(ExecutionStatus.FAILED)).when(original).fail();

        WorkflowExecutionResponse response = service.cancel(userId, executionId);

        verify(original).fail();
        verify(original).recordError("사용자가 취소");
        verify(workflowExecutionService, never()).markAsFailed(any(), any(), any());
        verify(workflowExecutionService, never()).markAsFailed(any(), any());
        assertThat(response.getStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    @DisplayName("cancel_WAITING_APPROVAL이면_409")
    void cancel_waitingApproval_returns409() {
        ownedBy(userId);
        given(original.getStatus()).willReturn(ExecutionStatus.WAITING_APPROVAL);

        assertThatThrownBy(() -> service.cancel(userId, executionId))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EXECUTION_NOT_WAITING_DELAY));
        verify(original, never()).fail();
    }

    @Test
    @DisplayName("cancel_남의_실행이면_WAITING_APPROVAL이어도_WORKFLOW_NOT_FOUND — 소유자 검증이 상태 검사보다 먼저")
    void cancel_notOwnerAndWaitingApproval_throwsWorkflowNotFound() {
        given(workflow.getId()).willReturn(workflowId);
        lenient().when(original.getStatus()).thenReturn(ExecutionStatus.WAITING_APPROVAL);
        given(workflowCrudService.getWorkflowByOwner(userId, workflowId))
            .willThrow(new CustomException(ErrorCode.WORKFLOW_NOT_FOUND));

        assertThatThrownBy(() -> service.cancel(userId, executionId))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_NOT_FOUND));
        verify(original, never()).fail();
        verify(original, never()).recordError(any());
    }

    @Test
    @DisplayName("cancel_남의_실행은_상태를_건드리지_않는다")
    void cancel_notOwner_leavesStateUntouched() {
        given(workflow.getId()).willReturn(workflowId);
        given(workflowCrudService.getWorkflowByOwner(userId, workflowId))
            .willThrow(new CustomException(ErrorCode.WORKFLOW_NOT_FOUND));

        assertThatThrownBy(() -> service.cancel(userId, executionId))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_NOT_FOUND));
        verify(original, never()).fail();
        verify(original, never()).recordError(any());
    }

    @Test
    @DisplayName("응답에_재개시각과_대기노드")
    void response_carriesResumeAtAndWaitingDelayNodeIds() {
        LocalDateTime t = local(NOW);
        lenient().when(original.getWorkflowVersion()).thenReturn(mock(WorkflowVersion.class));
        given(workflow.getId()).willReturn(workflowId);
        given(original.getResumeAt()).willReturn(t);
        given(original.waitingDelayNodeMap()).willReturn(Map.of(
            "d1", Map.of("scheduledFor", "2026-11-09T10:40:00+09:00"),
            "d2", Map.of("scheduledFor", "2026-11-09T12:30:00+09:00")));

        WorkflowExecutionResponse response = WorkflowExecutionResponse.from(original);

        assertThat(response.getResumeAt()).isEqualTo(t);
        assertThat(response.getWaitingDelayNodeIds()).containsExactlyInAnyOrder("d1", "d2");

        WorkflowExecution plain = mock(WorkflowExecution.class);
        given(plain.getWorkflow()).willReturn(workflow);
        given(plain.getWorkflowVersion()).willReturn(mock(WorkflowVersion.class));
        WorkflowExecutionResponse none = WorkflowExecutionResponse.from(plain);
        assertThat(none.getResumeAt()).isNull();
        assertThat(none.getWaitingDelayNodeIds()).isEmpty();
    }
}
