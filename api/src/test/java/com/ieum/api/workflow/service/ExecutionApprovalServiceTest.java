package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionApprovalServiceTest {

    @Mock private WorkflowCrudService workflowCrudService;
    @Mock private WorkflowExecutionService workflowExecutionService;
    @Mock private ExecutionRetryService executionRetryService;
    @InjectMocks private ExecutionApprovalService service;
    @Captor private ArgumentCaptor<Map<String, Object>> decision;

    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final UUID continuationId = UUID.randomUUID();

    private Workflow workflow;
    private WorkflowVersion version;

    @BeforeEach
    void setUp() {
        workflow = mock(Workflow.class);
        version = mock(WorkflowVersion.class);
    }

    /** 잠가 읽은 원 실행. 상태는 따로 세운다 — 소유권 검증에서 끊기면 상태를 읽지 않는다. */
    private WorkflowExecution lockedOriginal() {
        WorkflowExecution original = mock(WorkflowExecution.class);
        given(original.getWorkflow()).willReturn(workflow);
        given(workflow.getId()).willReturn(workflowId);
        given(workflowExecutionService.lockExecutionWithVersion(executionId)).willReturn(original);
        return original;
    }

    private WorkflowExecution waitingOriginal() {
        WorkflowExecution original = lockedOriginal();
        given(original.getStatus()).willReturn(ExecutionStatus.WAITING_APPROVAL);
        given(workflowCrudService.getWorkflowByOwner(userId, workflowId)).willReturn(workflow);
        return original;
    }

    @Test
    @DisplayName("승인: 게이트 SUCCESS 행(승인 정보) → 원 실행 SUCCESS → 이어진 실행 순서로 처리하고 새 실행을 돌려준다")
    void approve_recordsGatesClosesOriginalAndStartsContinuation() {
        WorkflowExecution original = waitingOriginal();
        WorkflowExecution continuation = mock(WorkflowExecution.class);
        given(continuation.getId()).willReturn(continuationId);
        given(continuation.getWorkflow()).willReturn(workflow);
        given(continuation.getWorkflowVersion()).willReturn(version);
        given(executionRetryService.startContinuation(workflow, original)).willReturn(continuation);

        WorkflowExecutionResponse response = service.approve(userId, executionId);

        assertThat(response.getId()).isEqualTo(continuationId);
        InOrder order = inOrder(workflowExecutionService, original, executionRetryService);
        order.verify(workflowExecutionService).recordApprovalDecision(
            eq(original), eq(ExecutionLogStatus.SUCCESS), decision.capture(), isNull());
        order.verify(original).complete();
        order.verify(executionRetryService).startContinuation(workflow, original);
        assertThat(decision.getValue())
            .containsEntry("approved", true)
            .containsEntry("approvedBy", userId.toString());
        // 하류가 문자열로 참조하는 ISO-8601 시각이어야 한다.
        assertThat(Instant.parse((String) decision.getValue().get("approvedAt"))).isNotNull();
        verify(original, never()).fail();
    }

    @Test
    @DisplayName("거부: 게이트 FAILED 행(거부자·사유) → 원 실행 FAILED + 사유. 사용자 결정이라 알림 경로(markAsFailed)를 타지 않는다")
    void reject_recordsGatesAndFailsWithoutAlert() {
        WorkflowExecution original = waitingOriginal();
        given(original.getWorkflowVersion()).willReturn(version);

        service.reject(userId, executionId, "금액 재확인 필요");

        verify(workflowExecutionService).recordApprovalDecision(eq(original),
            eq(ExecutionLogStatus.FAILED), decision.capture(), eq("승인 거부: 금액 재확인 필요"));
        assertThat(decision.getValue())
            .containsEntry("rejectedBy", userId.toString())
            .containsEntry("reason", "금액 재확인 필요");
        verify(original).fail();
        verify(original).recordError("승인 거부: 금액 재확인 필요");
        verify(original, never()).complete();
        verify(executionRetryService, never()).startContinuation(any(), any());
        verify(workflowExecutionService, never()).markAsFailed(any(), any());
    }

    @Test
    @DisplayName("거부 사유가 없으면 오류 문구는 '승인 거부'이고 결정 기록에 reason 키가 없다")
    void reject_withoutReason() {
        WorkflowExecution original = waitingOriginal();
        given(original.getWorkflowVersion()).willReturn(version);

        service.reject(userId, executionId, null);

        verify(workflowExecutionService).recordApprovalDecision(eq(original),
            eq(ExecutionLogStatus.FAILED), decision.capture(), eq("승인 거부"));
        assertThat(decision.getValue()).containsOnlyKeys("rejectedBy");
        verify(original).recordError("승인 거부");
    }

    @Test
    @DisplayName("대기 상태가 아닌 실행의 승인·거부는 409 — 승인 더블클릭의 두 번째 요청도 여기서 막힌다")
    void notWaiting_returns409() {
        for (ExecutionStatus status : new ExecutionStatus[] {
                ExecutionStatus.PENDING, ExecutionStatus.RUNNING,
                ExecutionStatus.SUCCESS, ExecutionStatus.FAILED}) {
            WorkflowExecution original = lockedOriginal();
            given(original.getStatus()).willReturn(status);
            given(workflowCrudService.getWorkflowByOwner(userId, workflowId)).willReturn(workflow);

            assertThatThrownBy(() -> service.approve(userId, executionId))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXECUTION_NOT_WAITING_APPROVAL);
            assertThatThrownBy(() -> service.reject(userId, executionId, "x"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXECUTION_NOT_WAITING_APPROVAL);
        }
        assertThat(ErrorCode.EXECUTION_NOT_WAITING_APPROVAL.getStatus().value()).isEqualTo(409);
        verify(workflowExecutionService, never()).recordApprovalDecision(any(), any(), any(), any());
        verify(executionRetryService, never()).startContinuation(any(), any());
    }

    @Test
    @DisplayName("소유자가 아니면 WORKFLOW_NOT_FOUND — 상태를 보기 전에 끊는다(ADMIN 우회 없음)")
    void notOwner_rejected() {
        lockedOriginal();
        given(workflowCrudService.getWorkflowByOwner(userId, workflowId))
            .willThrow(new CustomException(ErrorCode.WORKFLOW_NOT_FOUND));

        assertThatThrownBy(() -> service.approve(userId, executionId))
            .isInstanceOf(CustomException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.WORKFLOW_NOT_FOUND);
        assertThatThrownBy(() -> service.reject(userId, executionId, null))
            .isInstanceOf(CustomException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.WORKFLOW_NOT_FOUND);
        verify(workflowExecutionService, never()).recordApprovalDecision(any(), any(), any(), any());
    }

    @Test
    @DisplayName("실행 응답에 대기 게이트 ID와 승인 기한이 실린다 — 늦게 온 화면이 대기 노드를 알 수 있게")
    void response_exposesWaitingGatesAndDeadline() {
        LocalDateTime deadline = LocalDateTime.of(2026, 9, 30, 10, 0);
        WorkflowExecution execution = mock(WorkflowExecution.class);
        given(execution.getWorkflow()).willReturn(workflow);
        given(execution.getWorkflowVersion()).willReturn(version);
        given(execution.getStatus()).willReturn(ExecutionStatus.WAITING_APPROVAL);
        given(execution.waitingApprovalNodeIdList()).willReturn(List.of("gate-1"));
        given(execution.getApprovalDeadline()).willReturn(deadline);

        WorkflowExecutionResponse response = WorkflowExecutionResponse.from(execution);

        assertThat(response.getStatus()).isEqualTo(ExecutionStatus.WAITING_APPROVAL);
        assertThat(response.getWaitingApprovalNodeIds()).containsExactly("gate-1");
        assertThat(response.getApprovalDeadline()).isEqualTo(deadline);
    }
}
