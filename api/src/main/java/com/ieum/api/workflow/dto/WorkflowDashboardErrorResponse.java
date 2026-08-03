package com.ieum.api.workflow.dto;

import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.NodeType;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WorkflowDashboardErrorResponse {
    private final UUID executionId;
    private final UUID workflowId;
    private final String workflowName;
    private final String failedNodeId;
    private final NodeType failedNodeType;
    private final String errorMessage;
    private final LocalDateTime startedAt;
    private final LocalDateTime finishedAt;

    /**
     * @param errorMessage 실패한 노드 로그의 오류. 노드를 특정하지 못한 실패(프로세스 이상 종료로
     *                     고립된 실행 등)에는 노드 로그 자체가 없어 null이다 — 그 경우
     *                     실행 단위 실패 사유로 넘어간다
     */
    public static WorkflowDashboardErrorResponse of(
        WorkflowExecution execution,
        String failedNodeId,
        NodeType failedNodeType,
        String errorMessage
    ) {
        return WorkflowDashboardErrorResponse.builder()
            .executionId(execution.getId())
            .workflowId(execution.getWorkflow().getId())
            .workflowName(execution.getWorkflow().getName())
            .failedNodeId(failedNodeId)
            .failedNodeType(failedNodeType)
            .errorMessage(resolveErrorMessage(execution, errorMessage))
            .startedAt(execution.getStartedAt())
            .finishedAt(execution.getFinishedAt())
            .build();
    }

    /** 노드 로그의 오류 → 실행 단위 실패 사유 → 기본 문구 순으로 고른다. */
    private static String resolveErrorMessage(WorkflowExecution execution, String nodeErrorMessage) {
        if (nodeErrorMessage != null) {
            return nodeErrorMessage;
        }
        if (execution.getErrorMessage() != null) {
            return execution.getErrorMessage();
        }
        return "알 수 없는 에러가 발생했습니다.";
    }
}
