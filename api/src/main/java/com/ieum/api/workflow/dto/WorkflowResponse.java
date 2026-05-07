package com.ieum.api.workflow.dto;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowVersion;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WorkflowResponse {

    private UUID id;
    private UUID userId;
    private String name;
    private String description;
    private boolean active;
    private Integer version;
    private String nodesJson;
    private String edgesJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static WorkflowResponse from(Workflow workflow, WorkflowVersion latestVersion) {
        return WorkflowResponse.builder()
            .id(workflow.getId())
            .userId(workflow.getUserId())
            .name(workflow.getName())
            .description(workflow.getDescription())
            .active(workflow.isActive())
            .version(latestVersion != null ? latestVersion.getVersion() : null)
            .nodesJson(latestVersion != null ? latestVersion.getNodesJson() : null)
            .edgesJson(latestVersion != null ? latestVersion.getEdgesJson() : null)
            .createdAt(workflow.getCreatedAt())
            .updatedAt(workflow.getUpdatedAt())
            .build();
    }
}
