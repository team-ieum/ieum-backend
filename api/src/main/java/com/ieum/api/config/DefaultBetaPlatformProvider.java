package com.ieum.api.config;

import com.ieum.api.beta.config.BetaPlatformKeyProperties;
import com.ieum.api.beta.service.BetaQuotaService;
import com.ieum.auth.domain.User;
import com.ieum.auth.repository.UserRepository;
import com.ieum.workflowcore.engine.executor.BetaPlatformProvider;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * BetaPlatformProvider 실제 구현체 (어댑터).
 *
 * <p>api 모듈은 workflow-core와 auth/베타 쿼터 서비스 모두에 의존하므로,
 * workflow-core의 포트(BetaPlatformProvider)를 {@code UserRepository}(betaAccess) +
 * {@code BetaQuotaService}(Redis 쿼터)로 연결한다.
 *
 * <p>이 빈이 등록되면 workflow-core의 {@code StubBetaPlatformProvider}는
 * {@code @ConditionalOnMissingBean}에 의해 자동으로 비활성화된다.
 */
@Component
@RequiredArgsConstructor
public class DefaultBetaPlatformProvider implements BetaPlatformProvider {

    private final UserRepository userRepository;
    private final BetaQuotaService betaQuotaService;
    private final BetaPlatformKeyProperties properties;

    @Override
    public boolean isBetaEligible(UUID userId) {
        if (!properties.isEnabled()) {
            return false;
        }
        return userRepository.findById(userId)
            .map(User::isBetaAccess)
            .orElse(false);
    }

    @Override
    public void reserveQuota(UUID userId) {
        betaQuotaService.checkQuota(userId);
    }

    @Override
    public void recordTokens(UUID userId, long totalTokens) {
        betaQuotaService.addUsedTokens(userId, totalTokens);
    }
}
