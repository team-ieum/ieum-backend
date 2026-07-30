package com.ieum.api.workflow.service;

import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 실패한 실행(DLQ)을 다시 돌린다.
 *
 * <p>DLQ는 별도 저장소가 아니라 {@code status=FAILED}인 실행 목록 자체다. 재처리는 원 실행을
 * 되살리는 게 아니라 <b>같은 버전·같은 트리거 입력으로 새 실행을 만들어</b> 큐에 넣는 것이고,
 * 원 실행에는 {@code retriedByExecutionId}로 그 새 실행을 가리키는 링크만 남는다.
 *
 * <p>원 실행에서 이미 성공한 노드는 새 실행에서 executor를 부르지 않고 저장된 출력으로 대체된다.
 * 그 조회는 실행 시점에 {@link WorkflowExecutionRunner}가 하며(잡 큐 페이로드에 실을 수 없다),
 * 여기서는 링크만 세운다.
 *
 * <p><b>실행 계약은 at-least-once다.</b> 원 실행이 죽기 직전에 로그를 남기지 못한 노드는 부작용이
 * 이미 났더라도 스킵 대상이 아니라 다시 실행된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExecutionRetryService {

    private final WorkflowCrudService workflowCrudService;
    private final WorkflowExecutionService workflowExecutionService;
    private final WorkflowExecutionRunner workflowExecutionRunner;

    /**
     * 실패한 실행을 재처리할 새 실행을 만들어 큐에 투입한다.
     *
     * @throws CustomException EXECUTION_NOT_FOUND — 실행이 없음
     * @throws CustomException WORKFLOW_NOT_FOUND — 요청자가 워크플로우 소유자가 아님
     *                         (존재 여부를 흘리지 않도록 다른 조회 API와 같은 코드를 쓴다)
     * @throws CustomException EXECUTION_NOT_RETRYABLE — FAILED가 아닌 실행이거나,
     *                         아직 끝나지 않은 재처리가 이미 있는 경우
     */
    @Transactional
    public WorkflowExecutionResponse retryExecution(UUID userId, UUID executionId) {
        // 버전까지 즉시 로딩한다 — 아래에서 @Async 실행 스레드로 넘어가므로 lazy 프록시면
        // 큐 폴백 경로에서 정의를 읽을 때 LazyInitializationException이 난다.
        WorkflowExecution original = workflowExecutionService.getExecutionWithVersion(executionId);
        // 소유권 검증 — 다른 워크플로우 API와 동일하게 "소유자의 워크플로우로 조회"로 검사한다.
        Workflow workflow =
            workflowCrudService.getWorkflowByOwner(userId, original.getWorkflow().getId());

        if (original.getStatus() != ExecutionStatus.FAILED) {
            throw new CustomException(ErrorCode.EXECUTION_NOT_RETRYABLE,
                "현재 상태: " + original.getStatus());
        }

        // 진행 중인 재처리가 있으면 거부한다. 재처리 버튼 더블클릭만으로 두 재처리가 같은 실패 구간을
        // 동시에 실행해 중복 외부 호출이 나고(크래시 없이), 링크가 덮어써져 앞선 재처리가 회수될 때
        // 스킵 대상을 잃는다. 그 재처리가 끝난 뒤(SUCCESS/FAILED)에는 다시 재처리할 수 있다.
        UUID previousRetryId = original.getRetriedByExecutionId();
        if (previousRetryId != null) {
            ExecutionStatus previousStatus =
                workflowExecutionService.getExecution(previousRetryId).getStatus();
            if (previousStatus != ExecutionStatus.SUCCESS && previousStatus != ExecutionStatus.FAILED) {
                throw new CustomException(ErrorCode.EXECUTION_NOT_RETRYABLE,
                    "이미 재처리가 진행 중입니다 — retryExecutionId: " + previousRetryId
                        + ", 상태: " + previousStatus);
            }
        }

        // 원 실행이 고정한 버전을 그대로 쓴다 — 최신 버전으로 갈아타면 재처리가 아니라 새 실행이다.
        WorkflowVersion version = original.getWorkflowVersion();
        Map<String, Object> triggerData = workflowExecutionService.decryptTriggerData(original);

        WorkflowExecution retry = workflowExecutionService.prepareExecution(
            workflow, version, original.getTriggerType(), triggerData);
        original.markRetriedBy(retry.getId());

        final UUID retryExecutionId = retry.getId();
        // 커밋 후 투입 — 워커가 retriedByExecutionId 링크를 읽어야 스킵 대상을 알 수 있다.
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    workflowExecutionRunner.run(version, retryExecutionId, triggerData);
                }
            }
        );

        log.info("[ExecutionRetryService] 실패 실행 재처리 — originalId: {}, retryId: {}",
            executionId, retryExecutionId);
        return WorkflowExecutionResponse.from(retry);
    }
}
