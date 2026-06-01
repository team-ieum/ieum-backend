package com.ieum.api.workflow.dto;

import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.NodeType;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WorkflowDashboardErrorResponse {
    private final UUID executionId;
    private final UUID workflowId;
    private final String workflowName;
    private final String failedNodeId;
    private final NodeType failedNodeType;
    private final String errorMessage;
    private final LocalDateTime startedAt;
    private final LocalDateTime finishedAt;

    public static WorkflowDashboardErrorResponse of(
        WorkflowExecution execution,
        String failedNodeId,
        NodeType failedNodeType,
        String errorMessage
    ) {
        return WorkflowDashboardErrorResponse.builder()
            .executionId(execution.getId())
            .workflowId(execution.getWorkflow().getId())
            .workflowName(execution.getWorkflow().getName())
            .failedNodeId(failedNodeId)
            .failedNodeType(failedNodeType)
            .errorMessage(errorMessage != null ? errorMessage : "알 수 없는 에러가 발생했습니다.")
            .startedAt(execution.getStartedAt())
            .finishedAt(execution.getFinishedAt())
            .build();
    }
}
