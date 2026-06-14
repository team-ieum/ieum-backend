package com.ieum.api.integration.dto;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

/**
 * 연동 서비스별 워크플로우 목록의 경량 응답 DTO.
 *
 * <p>목록 화면용이므로 nodes/edges 전체는 포함하지 않고, 해당 서비스를 사용하는 노드 수
 * ({@code usedNodeCount})만 함께 제공한다.
 */
@Getter
@Builder
public class WorkflowSummaryResponse {

    private UUID id;
    private String name;
    private String description;
    private boolean active;
    private TriggerType triggerType;
    /** 해당 연동 서비스를 사용하는 노드 수 */
    private int usedNodeCount;
    private LocalDateTime updatedAt;

    public static WorkflowSummaryResponse from(Workflow workflow, int usedNodeCount) {
        return WorkflowSummaryResponse.builder()
            .id(workflow.getId())
            .name(workflow.getName())
            .description(workflow.getDescription())
            .active(workflow.isActive())
            .triggerType(workflow.getTriggerType())
            .usedNodeCount(usedNodeCount)
            .updatedAt(workflow.getUpdatedAt())
            .build();
    }
}
