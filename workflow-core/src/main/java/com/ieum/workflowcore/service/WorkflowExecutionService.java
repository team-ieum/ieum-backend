package com.ieum.workflowcore.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptor;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowExecutionLog;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.event.ExecutionEvent;
import com.ieum.workflowcore.engine.event.ExecutionEventSnapshot;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.repository.WorkflowQueryRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final WorkflowQueryRepository workflowQueryRepository;
    private final ObjectMapper objectMapper;
    private final AesEncryptor aesEncryptor;

    /**
     * 실행 전 검증 후 PENDING 상태의 실행 레코드를 생성한다.
     *
     * @param triggerData 트리거가 전달한 초기 입력. null 허용(수동 실행 등). 저장 전
     *                    {@link AesEncryptor}로 AES-256 암호화한다(OAuth 토큰·AI API Key와 동일 정책 —
     *                    웹훅 페이로드에 토큰이 실릴 수 있어 평문 저장은 금지).
     * @throws CustomException INVALID_WORKFLOW — 비활성화된 워크플로우이거나 버전이 없는 경우
     */
    @Transactional
    public WorkflowExecution prepareExecution(Workflow workflow, WorkflowVersion latestVersion,
            TriggerType triggerType, Map<String, Object> triggerData) {
        if (!workflow.isActive()) {
            throw new CustomException(ErrorCode.INVALID_WORKFLOW, "비활성화된 워크플로우입니다.");
        }

        WorkflowExecution execution = WorkflowExecution.builder()
            .workflow(workflow)
            .workflowVersion(latestVersion)
            .status(ExecutionStatus.PENDING)
            .triggerType(triggerType)
            .startedAt(LocalDateTime.now())
            .traceId(UUID.randomUUID().toString().replace("-", ""))
            .triggerData(encryptTriggerData(triggerData))
            .build();
        workflowExecutionRepository.save(execution);

        log.info("[WorkflowExecutionService] 실행 준비 완료 — workflowId: {}, executionId: {}",
            workflow.getId(), execution.getId());
        return execution;
    }

    /** 직렬화·암호화 실패가 실행 준비를 막지 않도록 warn만 남기고 null을 반환한다(saveExecutionLog와 동일 정책). */
    private String encryptTriggerData(Map<String, Object> triggerData) {
        if (triggerData == null) {
            return null;
        }
        try {
            return aesEncryptor.encrypt(objectMapper.writeValueAsString(triggerData));
        } catch (Exception e) {
            log.warn("[WorkflowExecutionService] triggerData 암호화 실패", e);
            return null;
        }
    }

    /**
     * 실행 이력을 필터·페이징 조회한다. hasNext 판별용으로 {@code size + 1}개를 반환하므로
     * 호출자가 초과분을 잘라내야 한다.
     */
    public List<WorkflowExecution> listExecutions(UUID workflowId, ExecutionStatus status,
            LocalDateTime from, LocalDateTime to, int page, int size) {
        return workflowQueryRepository.findExecutions(workflowId, status, from, to, page, size);
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

    /**
     * 실행 진행 SSE의 늦은 구독 보완용 스냅샷을 로드한다.
     *
     * <p>구독 시점까지 DB에 기록된 노드 로그를 이벤트로 변환하고, 실행이 이미 종료된 경우
     * 종료 이벤트까지 포함한다. 라이브 스트림 연결 여부는 {@code terminal} 플래그로 판단한다.
     *
     * @throws CustomException EXECUTION_NOT_FOUND / FORBIDDEN — 실행 미존재 또는 워크플로우 불일치
     */
    public ExecutionEventSnapshot loadEventSnapshot(UUID workflowId, UUID executionId) {
        WorkflowExecution execution = getExecution(executionId);
        if (!execution.getWorkflow().getId().equals(workflowId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }

        List<ExecutionEvent> events = new ArrayList<>();
        for (WorkflowExecutionLog logEntry :
                workflowExecutionLogRepository.findByExecutionIdOrderByCreatedAtAsc(executionId)) {
            events.add(toEvent(logEntry));
        }

        ExecutionStatus status = execution.getStatus();
        boolean terminal = status == ExecutionStatus.SUCCESS || status == ExecutionStatus.FAILED;
        if (terminal) {
            events.add(ExecutionEvent.executionCompleted(status));
        }
        return new ExecutionEventSnapshot(status, terminal, events);
    }

    private ExecutionEvent toEvent(WorkflowExecutionLog logEntry) {
        long duration = logEntry.getDurationMs() != null ? logEntry.getDurationMs() : 0L;
        if (logEntry.getStatus() == ExecutionLogStatus.FAILED) {
            return ExecutionEvent.nodeFailed(
                logEntry.getNodeId(), logEntry.getNodeType(), logEntry.getErrorMessage(), duration);
        }
        return ExecutionEvent.nodeCompleted(
            logEntry.getNodeId(), logEntry.getNodeType(), duration);
    }

    @Transactional
    public void markAsFailed(UUID executionId) {
        workflowExecutionRepository.findById(executionId).ifPresent(execution -> {
            if (execution.getStatus() != ExecutionStatus.FAILED
                    && execution.getStatus() != ExecutionStatus.SUCCESS) {
                execution.fail();
                log.warn("[ExecutionService] 실행 상태 FAILED 강제 업데이트 — executionId: {}", executionId);
            }
        });
    }
}
