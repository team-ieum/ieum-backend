package com.ieum.api.workflow.dto;

import com.ieum.workflowcore.repository.ApprovalGateRepository.OpenGate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

/** 내 승인 대기 목록의 한 행. {@code title}은 렌더된 message 앞 200자. */
@Getter
@Builder
public class ApprovalGateSummaryResponse {

    private UUID gateId;
    private UUID executionId;
    private UUID workflowId;
    private String workflowName;
    private String nodeId;
    private String event;
    private String title;
    private LocalDateTime deadline;
    private LocalDateTime createdAt;

    public static ApprovalGateSummaryResponse from(OpenGate gate, String workflowName) {
        return ApprovalGateSummaryResponse.builder()
            .gateId(gate.getId())
            .executionId(gate.getExecutionId())
            .workflowId(gate.getWorkflowId())
            .workflowName(workflowName)
            .nodeId(gate.getNodeId())
            .event(gate.getEvent())
            .title(gate.getTitle())
            .deadline(gate.getDeadline())
            .createdAt(gate.getCreatedAt())
            .build();
    }
}
