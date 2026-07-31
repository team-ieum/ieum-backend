package com.ieum.workflowcore.engine.executor;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * IdempotencyStore 임시 구현체.
 * api 모듈의 {@code DefaultIdempotencyStore}가 등록되면 자동으로 대체된다 (@ConditionalOnMissingBean).
 *
 * <p>항상 "마커 없었음"으로 처리한다 — 미구현 환경(workflow-core 단독 테스트 등)에서는
 * 중복 차단 없이 그대로 진행된다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(value = IdempotencyStore.class, ignored = StubIdempotencyStore.class)
public class StubIdempotencyStore implements IdempotencyStore {

    @Override
    public boolean markInFlight(String key, Duration ttl) {
        log.debug("[StubIdempotencyStore] IdempotencyStore 미구현 — key: {}", key);
        return true;
    }

    @Override
    public void clearInFlight(String key) {
        // no-op
    }
}
