package com.ieum.api.workflow.dto;

import com.ieum.workflowcore.domain.WorkflowExecutionLog;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.NodeType;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WorkflowExecutionLogResponse {

    private UUID id;
    private String nodeId;
    private NodeType nodeType;
    private ExecutionLogStatus status;
    private String inputJson;
    private String outputJson;
    private String errorMessage;
    private Long durationMs;
    private LocalDateTime createdAt;

    public static WorkflowExecutionLogResponse from(WorkflowExecutionLog log) {
        return WorkflowExecutionLogResponse.builder()
            .id(log.getId())
            .nodeId(log.getNodeId())
            .nodeType(log.getNodeType())
            .status(log.getStatus())
            .inputJson(log.getInputJson())
            .outputJson(log.getOutputJson())
            .errorMessage(log.getErrorMessage())
            .durationMs(log.getDurationMs())
            .createdAt(log.getCreatedAt())
            .build();
    }
}
