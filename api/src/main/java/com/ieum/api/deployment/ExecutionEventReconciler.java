package com.ieum.api.deployment;

import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.engine.event.ExecutionEvent;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Redis Pub/Sub is not durable. Reconcile observed streams with authoritative DB terminal state. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ieum.deployment.distributed.enabled", havingValue = "true")
public class ExecutionEventReconciler {
    private final ExecutionEventPublisher publisher;
    private final WorkflowExecutionRepository executions;

    @Scheduled(fixedDelayString = "${ieum.deployment.event-reconcile-interval:PT5S}")
    public void reconcile() {
        var observed = publisher.observedExecutionIds();
        if (observed.isEmpty()) return;
        try {
            for (var execution : executions.findAllById(observed)) {
                ExecutionStatus status = execution.getStatus();
                if (status == ExecutionStatus.SUCCESS || status == ExecutionStatus.FAILED
                        || status == ExecutionStatus.WAITING_APPROVAL) {
                    publisher.acceptRemote(execution.getId(), ExecutionEvent.executionCompleted(
                        execution.getId(), execution.getWorkflow().getId(), status));
                }
            }
        } catch (Exception e) { log.warn("Execution event terminal reconciliation failed; will retry", e); }
    }
}
