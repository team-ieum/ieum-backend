package com.ieum.workflowcore.service;

import com.fasterxml.jackson.core.type.TypeReference;
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
import com.ieum.workflowcore.engine.executor.AlertNotifier;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.repository.WorkflowQueryRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final AlertNotifier alertNotifier;

    /**
     * 실행 전 검증 후 PENDING 상태의 실행 레코드를 생성한다.
     *
     * @param triggerData 트리거가 전달한 초기 입력. null 허용(수동 실행 등). 저장 전
     *                    {@link AesEncryptor}로 AES-256 암호화한다(OAuth 토큰·AI API Key와 동일 정책 —
     *                    웹훅 페이로드에 토큰이 실릴 수 있어 평문 저장은 금지).
     * @throws CustomException INVALID_WORKFLOW — 비활성화된 워크플로우이거나 버전이 없는 경우
     * @throws CustomException 암호화·직렬화 실패 — 입력이 사라진 채 실행되지 않도록 실행 준비를 세운다
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

    /**
     * 트리거 입력을 암호화한다. 입력이 없으면(null·빈 Map) null을 저장한다.
     *
     * <p><b>비어 있지 않은 입력의 암호화 실패는 fail-fast다.</b> 여기서 null을 반환하면 실행은
     * 서지만 워커가 DB에서 읽을 때 입력이 사라진 채 돌아 <b>"입력 없이 SUCCESS"</b>로 기록된다 —
     * {@code {{trigger.data.*}}}를 쓰는 워크플로우가 빈 값으로 조용히 성공하는 게 가장 나쁜 결과다.
     * 암호화 실패는 AES 키 오설정 같은 시스템 결함이지 일시 장애가 아니므로 실행 준비를 세운다.
     */
    private String encryptTriggerData(Map<String, Object> triggerData) {
        if (triggerData == null || triggerData.isEmpty()) {
            return null;
        }
        try {
            return aesEncryptor.encrypt(objectMapper.writeValueAsString(triggerData));
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[WorkflowExecutionService] triggerData 직렬화 실패", e);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "트리거 입력을 저장하지 못했습니다.");
        }
    }

    /**
     * 저장된 트리거 입력을 복호해 실행에 넣을 수 있는 Map으로 되돌린다.
     * {@link #prepareExecution}의 암호화에 대응하는 유일한 역연산이며, 잡 큐 워커와
     * 실패 실행 재처리가 이 메서드 하나를 공유한다(복호 로직을 복제하지 말 것).
     *
     * <p>{@code triggerData}가 null이면 빈 Map을 반환한다 — 암호화 실패는 {@link #prepareExecution}에서
     * fail-fast로 걸러지므로, null은 "트리거 입력이 없었다"만 의미한다.
     *
     * @throws CustomException CREDENTIAL_DECRYPT_FAILED — 복호 또는 역직렬화 실패
     *                         (암호화 키 교체·데이터 손상)
     */
    public Map<String, Object> decryptTriggerData(WorkflowExecution execution) {
        if (execution.getTriggerData() == null) {
            return Map.of();
        }
        String json = aesEncryptor.decrypt(execution.getTriggerData());
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("[WorkflowExecutionService] triggerData 역직렬화 실패 — executionId: {}",
                execution.getId(), e);
            throw new CustomException(ErrorCode.CREDENTIAL_DECRYPT_FAILED);
        }
    }

    /**
     * 재처리 실행이 건너뛸 수 있는 노드의 출력을 원본 실행의 {@code node_runs}에서 읽어 온다.
     * 키는 nodeId, 값은 그 노드가 원본에서 남긴 output이다.
     *
     * <p>재처리로 만들어진 실행이 아니면 빈 Map을 반환한다 — 일반 실행은 이 결과가 비어 있어
     * 아무 노드도 건너뛰지 않는다. 잡 큐 페이로드가 executionId뿐이라 이 정보는 DB에서만 나온다.
     *
     * <p>{@code SKIPPED}도 함께 읽는다. 재처리를 다시 재처리할 때 앞선 재처리에서 이미 건너뛴
     * 노드를 되살려 실행하지 않기 위함이다.
     *
     * <p><b>output은 {@code SensitiveDataMasker}를 거쳐 저장된 값이다</b> — 민감 키는 {@code ***}로
     * 마스킹돼 있다. 뒤 노드가 앞 노드 출력의 자격증명을 참조하는 워크플로우라면 재처리에서
     * 마스킹된 값이 흘러간다.
     */
    public Map<String, Map<String, Object>> loadReusableNodeOutputs(UUID retryExecutionId) {
        Optional<WorkflowExecution> source =
            workflowExecutionRepository.findByRetriedByExecutionId(retryExecutionId);
        if (source.isEmpty()) {
            return Map.of();
        }

        Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        for (WorkflowExecutionLog logEntry : workflowExecutionLogRepository
                .findByExecutionIdAndStatusIn(source.get().getId(),
                    List.of(ExecutionLogStatus.SUCCESS, ExecutionLogStatus.SKIPPED))) {
            if (logEntry.getOutputJson() == null) {
                continue;
            }
            try {
                outputs.put(logEntry.getNodeId(),
                    objectMapper.readValue(logEntry.getOutputJson(),
                        new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                // 이 노드만 재실행된다 — 재처리 전체를 세울 이유는 없다.
                log.warn("[WorkflowExecutionService] 재사용 output 역직렬화 실패 — nodeId: {}",
                    logEntry.getNodeId(), e);
            }
        }
        log.info("[WorkflowExecutionService] 재처리 스킵 대상 노드 {}개 — executionId: {}, sourceId: {}",
            outputs.size(), retryExecutionId, source.get().getId());
        return outputs;
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

    /**
     * 실행 시점 버전까지 즉시 로딩해 조회한다. 조회한 버전을 트랜잭션·영속성 컨텍스트 밖
     * (@Async 실행 스레드 등)으로 넘길 때 쓴다 — {@link #getExecution}이 주는 lazy 프록시를
     * 그대로 넘기면 {@code LazyInitializationException}이 난다.
     */
    public WorkflowExecution getExecutionWithVersion(UUID executionId) {
        return workflowExecutionRepository.findWithVersionById(executionId)
            .orElseThrow(() -> new CustomException(ErrorCode.EXECUTION_NOT_FOUND));
    }

    /**
     * 실행 행을 잠근 뒤 버전까지 로딩해 돌려준다. 같은 실행을 두고 read-check-act를 하는 호출자
     * (실패 실행 재처리)가 동시 요청에 같은 상태를 두 번 읽지 않게 한다.
     *
     * <p><b>반드시 호출자 트랜잭션 안에서 불러라</b> — 락은 트랜잭션 종료까지만 유효하다.
     * 락 조회를 먼저 하는 순서가 중요하다: 잠그지 않고 먼저 읽으면 그 인스턴스가 영속성 컨텍스트에
     * 남아, 뒤이은 락 조회가 갱신된 DB 상태로 덮어쓰지 않아 stale 값으로 판단하게 된다.
     */
    public WorkflowExecution lockExecutionWithVersion(UUID executionId) {
        workflowExecutionRepository.findByIdForUpdate(executionId)
            .orElseThrow(() -> new CustomException(ErrorCode.EXECUTION_NOT_FOUND));
        return getExecutionWithVersion(executionId);
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

    /**
     * 런타임이 상태를 남기지 못한 실패를 FAILED로 확정한다(잡 페이로드 해석 실패, trigger_data 복호
     * 실패 등 런타임 진입 전 실패). 런타임이 이미 확정했으면 아무것도 하지 않는다.
     *
     * <p>알림은 상태 전이가 실제로 일어난 분기 안에서만 발신한다 — 런타임의
     * {@code finalizeFailure}가 이미 보냈으면 여기서 다시 보내지 않는다. 이 경로가 잡는 실패는
     * 개별 노드 실패보다 심각한 시스템 결함(AES 키 오설정, 큐 계약 파손)이라 조용히 넘기지 않는다.
     *
     * @param reason 알림 문구에 실릴 오류 요약. null이면 실패 원인 없이 발신된다
     */
    @Transactional
    public void markAsFailed(UUID executionId, String reason) {
        workflowExecutionRepository.findById(executionId).ifPresent(execution -> {
            if (execution.getStatus() != ExecutionStatus.FAILED
                    && execution.getStatus() != ExecutionStatus.SUCCESS) {
                execution.fail();
                log.warn("[ExecutionService] 실행 상태 FAILED 강제 업데이트 — executionId: {}", executionId);
                notifyFailure(execution, reason);
            }
        });
    }

    /**
     * 실패 알림 발신. 발신 실패는 warn만 남기고 삼킨다 — 알림이 상태 확정을 깨면 안 된다.
     *
     * <p>실패 노드를 특정할 수 없는 경로라 {@code failedNodeId}는 항상 null이고
     * {@code retryExhausted}는 false다. 소유자에게도 함께 발신한다 — 자기 실행이 실패한 사실은
     * 원인 노드를 몰라도 알아야 하고, 발신 지점을 대상별로 갈라 놓으면 분기만 늘어난다.
     */
    private void notifyFailure(WorkflowExecution execution, String reason) {
        try {
            Workflow workflow = execution.getWorkflow();
            alertNotifier.notifyExecutionFailed(new AlertNotifier.ExecutionFailureAlert(
                execution.getId(), workflow.getId(), workflow.getName(), workflow.getUserId(),
                null, reason, false));
        } catch (Exception e) {
            log.warn("[ExecutionService] 실패 알림 발신 실패 — executionId: {}, 상태 확정은 계속한다",
                execution.getId(), e);
        }
    }
}
