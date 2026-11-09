package com.ieum.api.deployment;

import static org.mockito.Mockito.*;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ExecutionEventReconcilerTest {
    @Test
    void dbTerminalStateClosesStreamAfterPubsubCompletionLoss() {
        var publisher = new ExecutionEventPublisher();
        var executions = mock(WorkflowExecutionRepository.class);
        var execution = mock(WorkflowExecution.class);
        var workflow = mock(Workflow.class);
        UUID id = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        when(execution.getId()).thenReturn(id);
        when(execution.getWorkflow()).thenReturn(workflow);
        when(workflow.getId()).thenReturn(workflowId);
        when(execution.getStatus()).thenReturn(ExecutionStatus.SUCCESS);
        when(executions.findAllById(any())).thenReturn(List.of(execution));
        StepVerifier.create(publisher.subscribe(id))
            .then(() -> new ExecutionEventReconciler(publisher, executions).reconcile())
            .expectNextMatches(event -> event.executionStatus() == ExecutionStatus.SUCCESS)
            .verifyComplete();
    }

    @Test
    void waitingDelay도_종료_이벤트를_받아들인다() {
        var publisher = spy(new ExecutionEventPublisher());
        var executions = mock(WorkflowExecutionRepository.class);
        var execution = mock(WorkflowExecution.class);
        var workflow = mock(Workflow.class);
        UUID id = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        when(execution.getId()).thenReturn(id);
        when(execution.getWorkflow()).thenReturn(workflow);
        when(workflow.getId()).thenReturn(workflowId);
        when(execution.getStatus()).thenReturn(ExecutionStatus.WAITING_DELAY);
        when(publisher.observedExecutionIds()).thenReturn(java.util.Set.of(id));
        when(executions.findAllById(any())).thenReturn(List.of(execution));

        new ExecutionEventReconciler(publisher, executions).reconcile();

        verify(publisher).acceptRemote(eq(id), argThat(event ->
            event.executionStatus() == ExecutionStatus.WAITING_DELAY));
    }
}
