package com.ieum.workflowcore.engine.executor;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * BetaPlatformProvider 임시 구현체.
 * api 모듈의 {@code DefaultBetaPlatformProvider}가 등록되면 자동으로 대체된다 (@ConditionalOnMissingBean).
 *
 * <p>항상 베타 비자격으로 처리한다 — 미구현 환경에서는 기존 keyless(self-hosted/거부) 경로만 동작한다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(value = BetaPlatformProvider.class, ignored = StubBetaPlatformProvider.class)
public class StubBetaPlatformProvider implements BetaPlatformProvider {

    @Override
    public boolean isBetaEligible(UUID userId) {
        log.debug("[StubBetaPlatformProvider] BetaPlatformProvider 미구현 — userId: {}", userId);
        return false;
    }

    @Override
    public String reserveQuota(UUID userId) {
        // no-op — 예약하지 않으므로 환불 대상 키도 없다
        return null;
    }

    @Override
    public void recordTokens(UUID userId, long totalTokens) {
        // no-op
    }

    @Override
    public void releaseDailyCall(String reservationKey) {
        // no-op
    }
}
