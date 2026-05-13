package com.ieum.api.workflow.dto;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowVersion;
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
    private Integer version;
    private List<NodeDto> nodes;
    private List<EdgeDto> edges;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 엔티티 → DTO 변환. JSON 파싱은 호출자(Service)가 담당하여 주입한다.
     */
    public static WorkflowResponse from(Workflow workflow, WorkflowVersion latestVersion,
            List<NodeDto> nodes, List<EdgeDto> edges) {
        return WorkflowResponse.builder()
            .id(workflow.getId())
            .userId(workflow.getUserId())
            .name(workflow.getName())
            .description(workflow.getDescription())
            .active(workflow.isActive())
            .version(latestVersion != null ? latestVersion.getVersion() : null)
            .nodes(nodes != null ? nodes : Collections.emptyList())
            .edges(edges != null ? edges : Collections.emptyList())
            .createdAt(workflow.getCreatedAt())
            .updatedAt(workflow.getUpdatedAt())
            .build();
    }
}
