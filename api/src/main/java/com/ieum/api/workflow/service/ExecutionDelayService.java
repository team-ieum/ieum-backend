package com.ieum.api.workflow.service;

import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기다리기(DELAY)에서 멈춘 {@code WAITING_DELAY} 실행의 재개.
 *
 * <p>승인과 같은 방식이다 — 원 실행을 되살리지 않고 DELAY 노드마다 SUCCESS 행(대기 출력 + waitedUntil)을 남긴 뒤
 * 원 실행을 SUCCESS로 닫고 {@link ExecutionRetryService#startContinuation}으로 이어진 실행을 만든다.
 * 원 실행 행을 잠그고 다시 보므로 sweeper·취소·승인이 겹쳐도 하나만 이긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExecutionDelayService {

    static final String INACTIVE_REASON = "워크플로우가 꺼져 있어 재개하지 않았어요";
    static final String CANCEL_REASON = "사용자가 취소";

    private final WorkflowExecutionService workflowExecutionService;
    private final ExecutionRetryService executionRetryService;
    private final WorkflowCrudService workflowCrudService;

    /**
     * 기다리는 실행을 FAILED로 끝낸다. 사용자 결정이라 알림을 거는 {@code markAsFailed}를 타지 않는다(승인 거부와 같다).
     *
     * @throws CustomException WORKFLOW_NOT_FOUND(비소유자) / EXECUTION_NOT_WAITING_DELAY(409)
     */
    @Transactional
    public WorkflowExecutionResponse cancel(UUID userId, UUID executionId) {
        WorkflowExecution execution = workflowExecutionService.lockExecutionWithVersion(executionId);
        workflowCrudService.getWorkflowByOwner(userId, execution.getWorkflow().getId());
        if (execution.getStatus() != ExecutionStatus.WAITING_DELAY) {
            throw new CustomException(ErrorCode.EXECUTION_NOT_WAITING_DELAY, "현재 상태: " + execution.getStatus());
        }
        execution.fail();
        execution.recordError(CANCEL_REASON);
        log.info("[ExecutionDelayService] 취소 — executionId: {}", executionId);
        return WorkflowExecutionResponse.from(execution);
    }

    /** @return 이어진 실행 ID. 아무것도 하지 않았으면 empty */
    @Transactional
    public Optional<UUID> resume(UUID executionId, Instant now) {
        WorkflowExecution original = workflowExecutionService.lockExecutionWithVersion(executionId);
        if (original.getStatus() != ExecutionStatus.WAITING_DELAY
                || original.getResumeAt() == null
                || original.getResumeAt().isAfter(LocalDateTime.ofInstant(now, ZoneId.systemDefault()))) {
            return Optional.empty();
        }
        Workflow workflow = original.getWorkflow();
        if (!workflow.isActive()) {
            // startContinuation보다 먼저 — 뒤집으면 INVALID_WORKFLOW로 롤백되어 영영 대기로 남는다. 실패 알림이 간다(A3).
            workflowExecutionService.markAsFailed(executionId, INACTIVE_REASON, ExecutionStatus.WAITING_DELAY);
            log.info("[ExecutionDelayService] 비활성 워크플로우라 재개하지 않음 — executionId: {}", executionId);
            return Optional.empty();
        }
        workflowExecutionService.recordDelayRelease(original, now);
        original.complete();
        WorkflowExecution next = executionRetryService.startContinuation(workflow, original);
        log.info("[ExecutionDelayService] 재개 — executionId: {}, continuationId: {}", executionId, next.getId());
        return Optional.of(next.getId());
    }
}
