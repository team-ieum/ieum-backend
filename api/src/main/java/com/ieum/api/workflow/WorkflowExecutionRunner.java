package com.ieum.api.workflow;

import com.ieum.api.workflow.queue.ExecutionJobQueue;
import com.ieum.api.deployment.ExecutionAdvisoryLocks;
import com.ieum.workflowcore.engine.ExecutionOwnershipGuard.OwnershipLostException;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.engine.SyncExecutionRuntime;
import com.ieum.workflowcore.engine.validation.WorkflowValidationException;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowExecutionRunner {

    private final SyncExecutionRuntime syncExecutionRuntime;
    private final WorkflowExecutionService workflowExecutionService;
    private final ExecutionJobQueue executionJobQueue;
    @Autowired(required = false)
    private ExecutionAdvisoryLocks advisoryLocks;
    @Autowired
    private WorkflowExecutionRepository executionRepository;
    private final AtomicInteger active = new AtomicInteger();

    public int activeCount() { return active.get(); }

    /**
     * 실행을 잡 큐에 넣는다. 큐에 들어간 잡은 같은 프로세스의 워커가 꺼내 실행하므로
     * 프로세스가 죽어도 다음 기동에서 회수된다.
     *
     * <p>단일 모드는 Redis 장애 시 직접 실행한다. 분산 모드는 DB PENDING 재발행을 기다린다.
     * 호출부는 큐 도입 전과 동일하게 fire-and-forget으로 쓰면 된다.
     */
    @Async("workflowExecutor")
    public void run(WorkflowVersion workflowVersion, UUID executionId,
            Map<String, Object> triggerData) {
        if (executionJobQueue.enqueue(executionId)) {
            return;
        }
        log.warn("[Runner] 큐 우회 직접 실행 — 프로세스가 죽으면 이 실행은 복구되지 않는다. executionId: {}",
            executionId);
        executeNow(workflowVersion, executionId, triggerData);
    }

    /**
     * 큐를 거치지 않고 호출 스레드에서 실행한다. 잡 큐 워커와 폴백 경로가 공유하는 유일한
     * 실행 지점이라 실패 기록(markAsFailed)이 한 곳에만 있다.
     *
     * <p>재처리로 만들어진 실행이면 원 실행에서 이미 성공한 노드의 출력을 여기서 조회해 런타임에
     * 넘긴다 — 잡 큐 페이로드에는 executionId밖에 없어 큐를 거쳐 온 실행도 같은 정보를 얻어야 한다.
     * 일반 실행은 빈 Map이 넘어가 기존과 동일하게 모든 노드를 실행한다.
     */
    public boolean executeNow(WorkflowVersion workflowVersion, UUID executionId,
            Map<String, Object> triggerData) {
        return executeWithInput(workflowVersion, executionId, () -> triggerData);
    }

    public boolean executeQueued(WorkflowExecution execution, UUID executionId) {
        return executeWithInput(execution.getWorkflowVersion(), executionId,
            () -> workflowExecutionService.decryptTriggerData(execution));
    }

    private boolean executeWithInput(WorkflowVersion workflowVersion, UUID executionId,
            Supplier<Map<String, Object>> input) {
        ExecutionAdvisoryLocks.Lease lease = null;
        if (advisoryLocks != null) {
            try {
                lease = advisoryLocks.tryAcquire(executionId);
                if (lease == null) return false;
                lease.assertHeld();
                // Recheck only after acquiring the shared lock. A different owner may have finished
                // between the worker's first lookup and this lock acquisition.
                var existing = executionRepository.findById(executionId);
                if (existing.isEmpty() || isTerminal(existing.get().getStatus())) {
                    lease.close();
                    return true;
                }
            } catch (Exception e) {
                closeLease(lease, executionId);
                log.warn("[Runner] 실행 잠금 획득/검증 실패 — pending 유지: {}", executionId, e);
                return false;
            }
        }
        active.incrementAndGet();
        try {
            syncExecutionRuntime.execute(workflowVersion, executionId, input.get(),
                workflowExecutionService.loadReusableNodeOutputs(executionId));
            if (lease != null) lease.assertHeld();
            return true;
        } catch (OwnershipLostException e) {
            log.warn("[Runner] 실행 소유권 상실 — 신규 부수 효과/종료 기록 중단, pending 유지: {}", executionId, e);
            return false;
        } catch (WorkflowValidationException e) {
            if (lease != null) {
                try { lease.assertHeld(); }
                catch (OwnershipLostException lost) { return false; }
            }
            // 정적 검증 차단은 사용자 설정 오류다 — 스택트레이스 없이 WARN 한 줄. 실행은 런타임 1단계가 이미 FAILED로 끝냈다.
            log.warn("[Runner] 정적 검증 차단 — executionId: {}, workflowId: {}, 사유: {}",
                executionId,
                workflowVersion.getWorkflow() != null ? workflowVersion.getWorkflow().getId() : null,
                e.getMessage());
            workflowExecutionService.markAsFailed(executionId, e.getMessage());
            return true;
        } catch (Exception e) {
            // Never overwrite a new owner's state after losing the original database session.
            if (lease != null) {
                try { lease.assertHeld(); }
                catch (OwnershipLostException lost) { return false; }
            }
            log.error("[Runner] 워크플로우 실행 예외 — executionId: {}", executionId, e);
            workflowExecutionService.markAsFailed(executionId, e.getMessage());
            return true;
        } finally {
            active.decrementAndGet();
            closeLease(lease, executionId);
        }
    }

    private boolean isTerminal(ExecutionStatus status) {
        return status == ExecutionStatus.SUCCESS || status == ExecutionStatus.FAILED
            || status == ExecutionStatus.WAITING_APPROVAL;
    }

    private void closeLease(ExecutionAdvisoryLocks.Lease lease, UUID executionId) {
        if (lease == null) return;
        try { lease.close(); }
        catch (Exception e) { log.error("Cannot release execution lock: {}", executionId, e); }
    }
}
