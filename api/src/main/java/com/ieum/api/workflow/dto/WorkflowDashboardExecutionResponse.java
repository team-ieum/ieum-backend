package com.ieum.api.workflow.dto;

import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WorkflowDashboardExecutionResponse {
    private final UUID id;
    private final UUID workflowId;
    private final String workflowName;
    private final ExecutionStatus status;
    private final Double durationSeconds;
    private final TriggerType triggerType;
    private final LocalDateTime startedAt;
    private final LocalDateTime finishedAt;

    public static WorkflowDashboardExecutionResponse from(WorkflowExecution execution) {
        Double duration = null;
        if (execution.getStartedAt() != null && execution.getFinishedAt() != null) {
            duration = java.time.Duration.between(execution.getStartedAt(), execution.getFinishedAt()).toMillis() / 1000.0;
        }
        return WorkflowDashboardExecutionResponse.builder()
            .id(execution.getId())
            .workflowId(execution.getWorkflow().getId())
            .workflowName(execution.getWorkflow().getName())
            .status(execution.getStatus())
            .durationSeconds(duration)
            .triggerType(execution.getTriggerType())
            .startedAt(execution.getStartedAt())
            .finishedAt(execution.getFinishedAt())
            .build();
    }
}
