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
public class WorkflowExecutionResponse {

    private UUID id;
    private UUID workflowId;
    private UUID workflowVersionId;
    private ExecutionStatus status;
    private TriggerType triggerType;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;

    public static WorkflowExecutionResponse from(WorkflowExecution execution) {
        return WorkflowExecutionResponse.builder()
            .id(execution.getId())
            .workflowId(execution.getWorkflow().getId())
            .workflowVersionId(execution.getWorkflowVersion().getId())
            .status(execution.getStatus())
            .triggerType(execution.getTriggerType())
            .startedAt(execution.getStartedAt())
            .finishedAt(execution.getFinishedAt())
            .createdAt(execution.getCreatedAt())
            .build();
    }
}
