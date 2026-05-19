package com.ieum.api.workflow;

import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.engine.SyncExecutionRuntime;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowExecutionRunner {

    private final SyncExecutionRuntime syncExecutionRuntime;
    private final WorkflowExecutionService workflowExecutionService;

    @Async("workflowExecutor")
    public void run(WorkflowVersion workflowVersion, UUID executionId,
            Map<String, Object> triggerData) {
        try {
            syncExecutionRuntime.execute(workflowVersion, executionId, triggerData);
        } catch (Exception e) {
            log.error("[Runner] 워크플로우 실행 예외 — executionId: {}", executionId, e);
            workflowExecutionService.markAsFailed(executionId);
        }
    }
}
