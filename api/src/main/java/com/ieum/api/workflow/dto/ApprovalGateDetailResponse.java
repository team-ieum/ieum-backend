package com.ieum.api.workflow.dto;

import com.ieum.workflowcore.domain.ApprovalGate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

/** 승인 게이트 상세. {@code view}는 저장된 화면용 config(렌더·마스킹된 {@code view_json}) 그대로다. */
@Getter
@Builder
public class ApprovalGateDetailResponse {

    private UUID gateId;
    private UUID executionId;
    private UUID workflowId;
    private String workflowName;
    private String nodeId;
    private String event;
    private Map<String, Object> view;
    private LocalDateTime deadline;
    private LocalDateTime createdAt;
    /** {@code approved}·{@code rejected}·{@code submitted}·{@code timeout}, 미결정이면 null */
    private String decision;
    private LocalDateTime decidedAt;

    public static ApprovalGateDetailResponse from(ApprovalGate gate, String workflowName, Map<String, Object> view) {
        return ApprovalGateDetailResponse.builder()
            .gateId(gate.getId())
            .executionId(gate.getExecutionId())
            .workflowId(gate.getWorkflowId())
            .workflowName(workflowName)
            .nodeId(gate.getNodeId())
            .event(gate.getEvent())
            .view(view)
            .deadline(gate.getDeadline())
            .createdAt(gate.getCreatedAt())
            .decision(gate.getDecision())
            .decidedAt(gate.getDecidedAt())
            .build();
    }
}
