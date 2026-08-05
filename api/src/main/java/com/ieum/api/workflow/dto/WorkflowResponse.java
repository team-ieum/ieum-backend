package com.ieum.api.workflow.dto;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
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
    private TriggerType triggerType;
    private String cronExpression;
    private Integer version;
    private List<NodeView> nodes;
    private List<EdgeView> edges;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static WorkflowResponse from(Workflow workflow, WorkflowVersion latestVersion,
            List<NodeView> nodes, List<EdgeView> edges) {
        return WorkflowResponse.builder()
            .id(workflow.getId())
            .userId(workflow.getUserId())
            .name(workflow.getName())
            .description(workflow.getDescription())
            .active(workflow.isActive())
            .triggerType(workflow.getTriggerType())
            .cronExpression(workflow.getCronExpression())
            .version(latestVersion != null ? latestVersion.getVersion() : null)
            .nodes(nodes != null ? nodes : Collections.emptyList())
            .edges(edges != null ? edges : Collections.emptyList())
            .createdAt(workflow.getCreatedAt())
            .updatedAt(workflow.getUpdatedAt())
            .build();
    }
}
