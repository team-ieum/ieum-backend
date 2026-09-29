package com.ieum.api.config;

import com.ieum.api.workflow.queue.ExecutionJobQueue;
import com.ieum.workflowcore.engine.executor.ExecutionJobEnqueuer;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * ExecutionJobEnqueuer 실제 구현체 (어댑터). api의 Redis Stream 잡 큐({@link ExecutionJobQueue})에 위임한다.
 *
 * <p>{@code @Primary}로 스캔 순서와 무관하게 항상 이 빈이 선택되도록 고정한다
 * (다른 Provider 포트와 동일한 이유 — {@code DefaultBetaPlatformProvider} 참고).
 * Stub이 선택되면 항상 false라 실행은 되지만 조용히 내구성만 잃는다.
 *
 * <p>큐 장애 처리(예외 대신 false)는 {@link ExecutionJobQueue#enqueue}가 이미 한다.
 */
@Component
@Primary
@RequiredArgsConstructor
public class DefaultExecutionJobEnqueuer implements ExecutionJobEnqueuer {

    private final ExecutionJobQueue executionJobQueue;

    @Override
    public boolean enqueue(UUID executionId) {
        return executionJobQueue.enqueue(executionId);
    }
}
