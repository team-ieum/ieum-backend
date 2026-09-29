package com.ieum.workflowcore.engine.executor;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * ExecutionJobEnqueuer 임시 구현체.
 * api 모듈의 {@code DefaultExecutionJobEnqueuer}가 등록되면 자동으로 대체된다 (@ConditionalOnMissingBean).
 *
 * <p>큐가 없으므로 항상 false를 반환한다 — 미구현 환경(workflow-core 단독 테스트 등)에서는
 * 호출부가 직접 실행으로 폴백한다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(value = ExecutionJobEnqueuer.class, ignored = StubExecutionJobEnqueuer.class)
public class StubExecutionJobEnqueuer implements ExecutionJobEnqueuer {

    @Override
    public boolean enqueue(UUID executionId) {
        log.debug("[StubExecutionJobEnqueuer] ExecutionJobEnqueuer 미구현 — executionId: {}", executionId);
        return false;
    }
}
