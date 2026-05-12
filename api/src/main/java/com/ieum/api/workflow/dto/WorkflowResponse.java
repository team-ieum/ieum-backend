package com.ieum.api.workflow.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowVersion;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
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

    public static WorkflowResponse from(Workflow workflow, WorkflowVersion latestVersion,
            ObjectMapper objectMapper) {
        List<NodeDto> nodes = Collections.emptyList();
        List<EdgeDto> edges = Collections.emptyList();
        Integer version = null;

        if (latestVersion != null) {
            version = latestVersion.getVersion();
            nodes = parseJson(latestVersion.getNodesJson(), new TypeReference<>() {}, objectMapper);
            edges = parseJson(latestVersion.getEdgesJson(), new TypeReference<>() {}, objectMapper);
        }

        return WorkflowResponse.builder()
            .id(workflow.getId())
            .userId(workflow.getUserId())
            .name(workflow.getName())
            .description(workflow.getDescription())
            .active(workflow.isActive())
            .version(version)
            .nodes(nodes)
            .edges(edges)
            .createdAt(workflow.getCreatedAt())
            .updatedAt(workflow.getUpdatedAt())
            .build();
    }

    private static <T> List<T> parseJson(String json, TypeReference<List<T>> typeRef,
            ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("[WorkflowResponse] JSON 파싱 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
