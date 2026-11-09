package com.ieum.api.workflow.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ieum.workflowcore.deployment.BackgroundOwnership;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DelayResumeSweeperTest {

    private final WorkflowExecutionService workflowExecutionService = mock(WorkflowExecutionService.class);
    private final ExecutionDelayService executionDelayService = mock(ExecutionDelayService.class);
    private final DelayResumeSweeper sweeper = new DelayResumeSweeper(workflowExecutionService, executionDelayService);

    @Test
    void 소유자가_아니면_조회도_안한다() {
        BackgroundOwnership ownership = mock(BackgroundOwnership.class);
        given(ownership.isOwner()).willReturn(false);
        ReflectionTestUtils.setField(sweeper, "backgroundOwnership", ownership);

        sweeper.sweep();

        verify(workflowExecutionService, never()).findDueDelayExecutionIds(any());
    }

    @Test
    void 한_건_실패해도_나머지는_재개() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        given(workflowExecutionService.findDueDelayExecutionIds(any())).willReturn(List.of(first, second));
        given(executionDelayService.resume(eq(first), any())).willThrow(new IllegalStateException("boom"));

        sweeper.sweep();

        verify(executionDelayService).resume(eq(second), any());
    }
}
