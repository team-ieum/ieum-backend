package com.ieum.api.workflow.queue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

class PendingExecutionRequeuerTest {
    @Test
    void durablePendingRowsAreRetriedAndScanProgressesPastBlockedRows() {
        var executions = mock(WorkflowExecutionRepository.class);
        var queue = mock(ExecutionJobQueue.class);
        var first = java.util.stream.IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toList();
        UUID later = UUID.randomUUID();
        when(executions.findPendingIdsBefore(any(), any()))
            .thenReturn(first, List.of(later), List.of());
        var requeuer = new PendingExecutionRequeuer(executions, queue);
        requeuer.recoverPending();
        requeuer.recoverPending();
        requeuer.recoverPending();
        verify(queue).enqueue(later);
        var pages = ArgumentCaptor.forClass(Pageable.class);
        verify(executions, times(3)).findPendingIdsBefore(any(), pages.capture());
        assertThat(pages.getAllValues().stream().map(Pageable::getPageNumber)).containsExactly(0, 1, 0);
    }
}
