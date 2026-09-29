package com.ieum.api.workflow.service;

import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 게이트(APPROVAL)에서 멈춘 실행의 승인·거부.
 *
 * <p>승인은 원 실행을 되살리지 않는다 — 대기 게이트마다 SUCCESS 노드 로그(출력 = 승인 정보)를 남기고
 * 원 실행을 SUCCESS로 닫은 뒤, 재처리와 같은 경로({@link ExecutionRetryService#startContinuation})로
 * 이어진 실행을 만든다. 이어진 실행은 원 실행의 SUCCESS·SKIPPED 출력을 재사용하므로 게이트까지 SKIPPED로
 * 지나가고 게이트 하류부터 실제로 실행된다. 하류에 또 게이트가 있으면 이어진 실행이 거기서 다시 멈춘다.
 *
 * <p>거부는 게이트마다 FAILED 로그를 남기고 원 실행을 FAILED로 닫는다. 사용자가 내린 결정이라 실패
 * 알림을 보내지 않는다 — 그래서 알림을 거는 {@code markAsFailed}를 타지 않는다.
 *
 * <p>원 실행 행을 잠그고 상태를 본다. 승인·거부·만료(sweeper의 {@code markAsFailed}도 행 잠금)가 동시에
 * 와도 하나만 이기고, 나머지는 대기 상태가 아니게 된 행을 보고 409가 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExecutionApprovalService {

    private final WorkflowCrudService workflowCrudService;
    private final WorkflowExecutionService workflowExecutionService;
    private final ExecutionRetryService executionRetryService;

    /**
     * @return 이어진 새 실행 — 프론트는 이 ID로 SSE를 다시 구독한다
     * @throws CustomException EXECUTION_NOT_FOUND / WORKFLOW_NOT_FOUND(비소유자, 존재 여부를 흘리지 않는다)
     *                         / EXECUTION_NOT_WAITING_APPROVAL(409) / INVALID_WORKFLOW(대기 중 비활성화된 워크플로우 —
     *                         롤백되어 실행은 대기로 남고 결국 만료된다)
     */
    @Transactional
    public WorkflowExecutionResponse approve(UUID userId, UUID executionId) {
        WorkflowExecution original = workflowExecutionService.lockExecutionWithVersion(executionId);
        Workflow workflow =
            workflowCrudService.getWorkflowByOwner(userId, original.getWorkflow().getId());
        requireWaiting(original);

        // 하류가 {{nodes.<gate>.output.approvedBy}}로 참조하는 게이트 출력이다.
        Map<String, Object> decision = Map.of(
            "approved", true,
            "approvedBy", userId.toString(),
            "approvedAt", Instant.now().toString());
        workflowExecutionService.recordApprovalDecision(
            original, ExecutionLogStatus.SUCCESS, decision, null);
        original.complete();
        WorkflowExecution continuation = executionRetryService.startContinuation(workflow, original);

        log.info("[ExecutionApprovalService] 승인 — originalId: {}, continuationId: {}",
            executionId, continuation.getId());
        return WorkflowExecutionResponse.from(continuation);
    }

    /**
     * @param reason 선택. 200자 제한은 요청 DTO가 건다
     * @return FAILED로 닫힌 원 실행
     */
    @Transactional
    public WorkflowExecutionResponse reject(UUID userId, UUID executionId, String reason) {
        WorkflowExecution original = workflowExecutionService.lockExecutionWithVersion(executionId);
        workflowCrudService.getWorkflowByOwner(userId, original.getWorkflow().getId());
        requireWaiting(original);

        boolean hasReason = reason != null && !reason.isBlank();
        String errorMessage = hasReason ? "승인 거부: " + reason.strip() : "승인 거부";
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("rejectedBy", userId.toString());
        if (hasReason) {
            decision.put("reason", reason.strip());
        }
        workflowExecutionService.recordApprovalDecision(
            original, ExecutionLogStatus.FAILED, decision, errorMessage);
        original.fail();
        original.recordError(errorMessage);

        log.info("[ExecutionApprovalService] 거부 — executionId: {}", executionId);
        return WorkflowExecutionResponse.from(original);
    }

    private void requireWaiting(WorkflowExecution execution) {
        if (execution.getStatus() != ExecutionStatus.WAITING_APPROVAL) {
            throw new CustomException(ErrorCode.EXECUTION_NOT_WAITING_APPROVAL,
                "현재 상태: " + execution.getStatus());
        }
    }
}
