package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ExecutionRetryServiceTest {

    @Mock private WorkflowCrudService workflowCrudService;
    @Mock private WorkflowExecutionService workflowExecutionService;
    @Mock private WorkflowExecutionRunner workflowExecutionRunner;
    @InjectMocks private ExecutionRetryService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final UUID originalId = UUID.randomUUID();
    private final UUID retryId = UUID.randomUUID();

    private Workflow workflow;
    private WorkflowVersion version;

    @BeforeEach
    void setUp() {
        // 서비스가 afterCommit 콜백을 등록하므로 동기화를 활성화해 둔다.
        TransactionSynchronizationManager.initSynchronization();
        workflow = mock(Workflow.class);
        version = mock(WorkflowVersion.class);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private WorkflowExecution originalExecution(ExecutionStatus status) {
        WorkflowExecution execution = mock(WorkflowExecution.class);
        given(execution.getWorkflow()).willReturn(workflow);
        given(workflow.getId()).willReturn(workflowId);
        given(execution.getStatus()).willReturn(status);
        return execution;
    }

    private WorkflowExecution retryExecution() {
        WorkflowExecution retry = mock(WorkflowExecution.class);
        given(retry.getId()).willReturn(retryId);
        given(retry.getWorkflow()).willReturn(workflow);
        given(retry.getWorkflowVersion()).willReturn(version);
        given(retry.getStatus()).willReturn(ExecutionStatus.PENDING);
        given(retry.getTriggerType()).willReturn(TriggerType.MANUAL);
        given(retry.getStartedAt()).willReturn(LocalDateTime.now());
        given(version.getId()).willReturn(UUID.randomUUID());
        return retry;
    }

    @Test
    @DisplayName("소유자가 아니면 재처리를 거부한다")
    void 남의_실행은_재처리할_수_없다() {
        WorkflowExecution original = mock(WorkflowExecution.class);
        Workflow otherWorkflow = mock(Workflow.class);
        given(otherWorkflow.getId()).willReturn(workflowId);
        given(original.getWorkflow()).willReturn(otherWorkflow);
        given(workflowExecutionService.getExecutionWithVersion(originalId)).willReturn(original);
        given(workflowCrudService.getWorkflowByOwner(userId, workflowId))
            .willThrow(new CustomException(ErrorCode.WORKFLOW_NOT_FOUND));

        assertThatThrownBy(() -> service.retryExecution(userId, originalId))
            .isInstanceOf(CustomException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.WORKFLOW_NOT_FOUND);

        verify(workflowExecutionService, never())
            .prepareExecution(any(), any(), any(), any());
        verify(workflowExecutionRunner, never()).run(any(), any(), any());
    }

    @Test
    @DisplayName("FAILED가 아닌 실행은 400으로 거부한다")
    void 실패하지_않은_실행은_재처리_불가() {
        for (ExecutionStatus status :
                new ExecutionStatus[] {ExecutionStatus.PENDING, ExecutionStatus.RUNNING,
                    ExecutionStatus.SUCCESS}) {
            WorkflowExecution original = originalExecution(status);
            given(workflowExecutionService.getExecutionWithVersion(originalId)).willReturn(original);
            given(workflowCrudService.getWorkflowByOwner(userId, workflowId)).willReturn(workflow);

            assertThatThrownBy(() -> service.retryExecution(userId, originalId))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXECUTION_NOT_RETRYABLE);
        }
        assertThat(ErrorCode.EXECUTION_NOT_RETRYABLE.getStatus().value()).isEqualTo(400);
        verify(workflowExecutionService, never()).prepareExecution(any(), any(), any(), any());
    }

    @Test
    @DisplayName("진행 중인 재처리가 있으면 두 번째 요청을 거부한다 — 더블클릭 중복 실행 차단")
    void 진행중인_재처리가_있으면_거부() {
        for (ExecutionStatus inFlight :
                new ExecutionStatus[] {ExecutionStatus.PENDING, ExecutionStatus.RUNNING}) {
            WorkflowExecution original = originalExecution(ExecutionStatus.FAILED);
            given(original.getRetriedByExecutionId()).willReturn(retryId);
            WorkflowExecution previousRetry = mock(WorkflowExecution.class);
            given(previousRetry.getStatus()).willReturn(inFlight);

            given(workflowExecutionService.getExecutionWithVersion(originalId)).willReturn(original);
            given(workflowCrudService.getWorkflowByOwner(userId, workflowId)).willReturn(workflow);
            given(workflowExecutionService.getExecution(retryId)).willReturn(previousRetry);

            assertThatThrownBy(() -> service.retryExecution(userId, originalId))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXECUTION_NOT_RETRYABLE);
        }

        verify(workflowExecutionService, never()).prepareExecution(any(), any(), any(), any());
        verify(workflowExecutionRunner, never()).run(any(), any(), any());
    }

    @Test
    @DisplayName("앞선 재처리가 끝났으면(FAILED) 다시 재처리할 수 있다 — 재재처리 기능 손실 없음")
    void 끝난_재처리는_다시_재처리_가능() {
        WorkflowExecution original = originalExecution(ExecutionStatus.FAILED);
        given(original.getRetriedByExecutionId()).willReturn(UUID.randomUUID());
        given(original.getWorkflowVersion()).willReturn(version);
        given(original.getTriggerType()).willReturn(TriggerType.MANUAL);
        WorkflowExecution previousRetry = mock(WorkflowExecution.class);
        given(previousRetry.getStatus()).willReturn(ExecutionStatus.FAILED);

        given(workflowExecutionService.getExecutionWithVersion(originalId)).willReturn(original);
        given(workflowCrudService.getWorkflowByOwner(userId, workflowId)).willReturn(workflow);
        given(workflowExecutionService.getExecution(original.getRetriedByExecutionId()))
            .willReturn(previousRetry);
        given(workflowExecutionService.decryptTriggerData(original)).willReturn(Map.of());
        WorkflowExecution retry = retryExecution();
        given(workflowExecutionService.prepareExecution(
            workflow, version, TriggerType.MANUAL, Map.of())).willReturn(retry);

        assertThat(service.retryExecution(userId, originalId).getId()).isEqualTo(retryId);
        verify(original).markRetriedBy(retryId);
    }

    @Test
    @DisplayName("FAILED 실행은 원 버전·복호한 트리거 입력으로 새 실행을 만들고 원 실행에 링크를 남긴다")
    void 실패_실행_재처리() {
        WorkflowExecution original = originalExecution(ExecutionStatus.FAILED);
        given(original.getWorkflowVersion()).willReturn(version);
        given(original.getTriggerType()).willReturn(TriggerType.WEBHOOK);
        Map<String, Object> triggerData = Map.of("score", "85");

        given(workflowExecutionService.getExecutionWithVersion(originalId)).willReturn(original);
        given(workflowCrudService.getWorkflowByOwner(userId, workflowId)).willReturn(workflow);
        given(workflowExecutionService.decryptTriggerData(original)).willReturn(triggerData);
        WorkflowExecution retry = retryExecution();
        given(workflowExecutionService.prepareExecution(
            workflow, version, TriggerType.WEBHOOK, triggerData)).willReturn(retry);

        WorkflowExecutionResponse response = service.retryExecution(userId, originalId);

        assertThat(response.getId()).isEqualTo(retryId);
        verify(original).markRetriedBy(retryId);
        // 트리거 입력은 반드시 decryptTriggerData를 거친다 — trigger_data는 암호문이다.
        verify(workflowExecutionService).decryptTriggerData(original);

        // 커밋 전에는 아직 투입하지 않는다 — 워커가 재처리 링크를 못 읽는 순간이 없어야 한다.
        verify(workflowExecutionRunner, never()).run(any(), any(), any());
        for (TransactionSynchronization sync :
                TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        verify(workflowExecutionRunner).run(eq(version), eq(retryId), eq(triggerData));
    }
}
