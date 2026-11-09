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
    /** 기다리기(DELAY) 재개 시각. 기다린 적 없는 실행은 null */
    private LocalDateTime resumeAt;
    /**
     * 기다리기에서 멈췄을 때 대기 중인 DELAY 노드 ID. 없던 실행은 빈 배열.
     * 재개·취소 뒤에도 이력으로 남는다 — "기다리는 중"은 {@code status = WAITING_DELAY}일 때만의 의미다.
     */
    private List<String> waitingDelayNodeIds;
    /**
     * 결정 안 된 게이트 노드 ID. 게이트 처리 응답만 게이트 행에서 계산하고, 나머지 응답(목록 포함)은
     * N+1을 피해 {@code waitingApprovalNodeIds}를 그대로 싣는다.
     */
    private List<String> pendingApprovalNodeIds;

    public static WorkflowExecutionResponse from(WorkflowExecution execution) {
        return from(execution, execution.waitingApprovalNodeIdList());
    }

    /** 게이트 처리 응답 — 원 실행이면 남은 게이트, 이어진 실행이면 빈 목록. */
    public static WorkflowExecutionResponse from(WorkflowExecution execution, List<String> pendingApprovalNodeIds) {
        return WorkflowExecutionResponse.builder()
            .pendingApprovalNodeIds(pendingApprovalNodeIds)
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
            .resumeAt(execution.getResumeAt())
            .waitingDelayNodeIds(List.copyOf(execution.waitingDelayNodeMap().keySet()))
            .build();
    }
}
