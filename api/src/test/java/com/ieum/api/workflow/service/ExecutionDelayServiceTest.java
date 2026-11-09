package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
    @InjectMocks private ExecutionDelayService service;

    private final UUID executionId = UUID.randomUUID();
    private final UUID continuationId = UUID.randomUUID();
    private WorkflowExecution original;
    private Workflow workflow;

    @BeforeEach
    void setUp() {
        original = mock(WorkflowExecution.class);
        workflow = mock(Workflow.class);
        given(workflowExecutionService.lockExecutionWithVersion(executionId)).willReturn(original);
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
    void resume_재개시각_지나면_DELAY_해제_후_이어진실행() {
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
    void resume_비활성이면_실패_알림_경로() {
        given(workflow.isActive()).willReturn(false);

        Optional<UUID> result = service.resume(executionId, NOW);

        verify(workflowExecutionService).markAsFailed(
            executionId, "워크플로우가 꺼져 있어 재개하지 않았어요", ExecutionStatus.WAITING_DELAY);
        verify(workflowExecutionService, never()).recordDelayRelease(any(), any());
        verify(executionRetryService, never()).startContinuation(any(), any());
        assertThat(result).isEmpty();
    }

    @Test
    void resume_이미_취소된_실행은_건드리지_않는다() {
        given(original.getStatus()).willReturn(ExecutionStatus.FAILED);

        assertThat(service.resume(executionId, NOW)).isEmpty();
        verifyNoWrites();
    }

    @Test
    void resume_재개시각_전이면_no_op() {
        given(original.getResumeAt()).willReturn(local(NOW.plus(Duration.ofMinutes(1))));

        assertThat(service.resume(executionId, NOW)).isEmpty();
        verifyNoWrites();
    }
}
