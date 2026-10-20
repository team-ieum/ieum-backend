package com.ieum.api.workflow.queue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.api.deployment.DeploymentControl;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class PendingExecutionRequeuerTest {
    private WorkflowExecutionRepository executions;
    private ExecutionJobQueue queue;
    private DeploymentControl control;
    private PendingExecutionRequeuer requeuer;

    @BeforeEach
    void setUp() {
        executions = mock(WorkflowExecutionRepository.class);
        queue = mock(ExecutionJobQueue.class);
        control = mock(DeploymentControl.class);
        when(control.isOwner()).thenReturn(true);
        when(executions.findPendingIdsBetween(any(), any(), any())).thenReturn(List.of());
        requeuer = new PendingExecutionRequeuer(executions, queue, control);
        ReflectionTestUtils.setField(requeuer, "notBefore", Instant.now().minusSeconds(86400).toString());
        requeuer.validatePolicy();
    }

    @Test
    void missingOrUnzonedCutoffFailsStartupRatherThanSilentlyReplayingHistoricalRows() {
        ReflectionTestUtils.setField(requeuer, "notBefore", "");
        assertThatThrownBy(requeuer::validatePolicy).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(requeuer, "notBefore", "2026-10-20T12:00:00");
        assertThatThrownBy(requeuer::validatePolicy).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(queue);
    }

    @Test
    void candidateAndRetainedInstancesNeverAutomaticallyRequeue() {
        when(control.isOwner()).thenReturn(false);
        requeuer.recoverPending();
        verify(executions, never()).findPendingIdsBetween(any(), any(), any());
        verifyNoInteractions(queue);
    }

    @Test
    void rowsAreRestrictedToRecentHourEvenWithAnOldCutoff() {
        LocalDateTime before = LocalDateTime.now();
        requeuer.recoverPending();
        var lower = ArgumentCaptor.forClass(LocalDateTime.class);
        var upper = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(executions).findPendingIdsBetween(lower.capture(), upper.capture(), any());
        assertThat(lower.getValue()).isBetween(before.minusHours(1), LocalDateTime.now().minusHours(1));
        assertThat(upper.getValue()).isBetween(before.minusMinutes(1), LocalDateTime.now().minusMinutes(1));
    }

    @Test
    void aMoreRecentOperatorCutoffIsAnExactLowerBoundInJvmDatabaseTime() {
        Instant cutoff = Instant.now().minusSeconds(600);
        ReflectionTestUtils.setField(requeuer, "notBefore", cutoff.toString());
        requeuer.validatePolicy();
        requeuer.recoverPending();
        verify(executions).findPendingIdsBetween(
            eq(LocalDateTime.ofInstant(cutoff, ZoneId.systemDefault())), any(), any());
    }

    @Test
    void durablePendingRowsAreRetriedAndScanProgressesPastBlockedRows() {
        var first = java.util.stream.IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toList();
        UUID later = UUID.randomUUID();
        when(executions.findPendingIdsBetween(any(), any(), any()))
            .thenReturn(first, List.of(later), List.of());
        requeuer.recoverPending();
        requeuer.recoverPending();
        requeuer.recoverPending();
        verify(queue).requeuePending(later, 3);
        var pages = ArgumentCaptor.forClass(Pageable.class);
        verify(executions, times(3)).findPendingIdsBetween(any(), any(), pages.capture());
        assertThat(pages.getAllValues().stream().map(Pageable::getPageNumber)).containsExactly(0, 1, 0);
    }

    @Test
    void ownershipLossDuringBatchStopsFurtherPublication() {
        when(executions.findPendingIdsBetween(any(), any(), any()))
            .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));
        when(control.isOwner()).thenReturn(true, true, false);
        requeuer.recoverPending();
        verify(queue, times(1)).requeuePending(any(), eq(3));
    }
}
