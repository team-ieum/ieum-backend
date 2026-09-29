package com.ieum.api.workflow.dto;

import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.time.LocalDateTime;
import java.util.List;
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
    /**
     * 승인 게이트에서 멈췄을 때 대기 중인 게이트 노드 ID. 게이트가 없던 실행은 빈 배열.
     * 승인·거부 뒤에도 이력으로 남는다 — "대기 중"은 {@code status = WAITING_APPROVAL}일 때만의 의미다.
     */
    private List<String> waitingApprovalNodeIds;
    /** 승인 기한. 지나면 최대 10분(sweeper 주기) 안에 FAILED("승인 만료")가 된다 */
    private LocalDateTime approvalDeadline;

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
            .waitingApprovalNodeIds(execution.waitingApprovalNodeIdList())
            .approvalDeadline(execution.getApprovalDeadline())
            .build();
    }
}
