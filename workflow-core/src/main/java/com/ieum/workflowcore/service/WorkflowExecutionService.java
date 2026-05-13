package com.ieum.workflowcore.service;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowExecutionLog;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워크플로우 실행(Execution) 비즈니스 로직.
 *
 * <p>실행 레코드 생성/조회를 담당한다. 실제 비동기 실행 트리거는
 * api 모듈의 {@code WorkflowExecutionRunner}가 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowExecutionService {

    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final WorkflowExecutionLogRepository workflowExecutionLogRepository;

    /**
     * 실행 전 검증 후 PENDING 상태의 실행 레코드를 생성한다.
     *
     * @throws CustomException INVALID_WORKFLOW — 비활성화된 워크플로우이거나 버전이 없는 경우
     */
    @Transactional
    public WorkflowExecution prepareExecution(Workflow workflow, WorkflowVersion latestVersion,
            TriggerType triggerType) {
        if (!workflow.isActive()) {
            throw new CustomException(ErrorCode.INVALID_WORKFLOW, "비활성화된 워크플로우입니다.");
        }

        WorkflowExecution execution = WorkflowExecution.builder()
            .workflow(workflow)
            .workflowVersion(latestVersion)
            .status(ExecutionStatus.PENDING)
            .triggerType(triggerType)
            .startedAt(LocalDateTime.now())
            .build();
        workflowExecutionRepository.save(execution);

        log.info("[WorkflowExecutionService] 실행 준비 완료 — workflowId: {}, executionId: {}",
            workflow.getId(), execution.getId());
        return execution;
    }

    public List<WorkflowExecution> listExecutions(UUID workflowId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return workflowExecutionRepository.findByWorkflowId(workflowId, pageable);
    }

    public boolean hasNextExecutions(UUID workflowId, int page, int size) {
        Pageable pageable = PageRequest.of(page + 1, 1, Sort.by("createdAt").descending());
        return !workflowExecutionRepository.findByWorkflowId(workflowId, pageable).isEmpty();
    }

    public WorkflowExecution getExecution(UUID executionId) {
        return workflowExecutionRepository.findById(executionId)
            .orElseThrow(() -> new CustomException(ErrorCode.EXECUTION_NOT_FOUND));
    }

    public List<WorkflowExecutionLog> listExecutionLogs(UUID workflowId, UUID executionId) {
        WorkflowExecution execution = getExecution(executionId);
        if (!execution.getWorkflow().getId().equals(workflowId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
        return workflowExecutionLogRepository.findByExecutionIdOrderByCreatedAtAsc(executionId);
    }
}
