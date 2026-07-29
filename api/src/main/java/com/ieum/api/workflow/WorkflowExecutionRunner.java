package com.ieum.api.workflow;

import com.ieum.api.workflow.queue.ExecutionJobQueue;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.engine.SyncExecutionRuntime;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowExecutionRunner {

    private final SyncExecutionRuntime syncExecutionRuntime;
    private final WorkflowExecutionService workflowExecutionService;
    private final ExecutionJobQueue executionJobQueue;

    /**
     * 실행을 잡 큐에 넣는다. 큐에 들어간 잡은 같은 프로세스의 워커가 꺼내 실행하므로
     * 프로세스가 죽어도 다음 기동에서 회수된다.
     *
     * <p>큐를 못 쓰면(Redis 장애) 이 스레드에서 바로 실행한다 — 내구성은 잃지만 실행은 성공한다.
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
     */
    public void executeNow(WorkflowVersion workflowVersion, UUID executionId,
            Map<String, Object> triggerData) {
        try {
            syncExecutionRuntime.execute(workflowVersion, executionId, triggerData);
        } catch (Exception e) {
            log.error("[Runner] 워크플로우 실행 예외 — executionId: {}", executionId, e);
            workflowExecutionService.markAsFailed(executionId);
        }
    }
}
