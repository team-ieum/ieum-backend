package com.ieum.api.config;

import com.ieum.api.beta.config.BetaPlatformKeyProperties;
import com.ieum.api.beta.service.BetaQuotaService;
import com.ieum.auth.domain.User;
import com.ieum.auth.repository.UserRepository;
import com.ieum.workflowcore.engine.executor.BetaPlatformProvider;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * BetaPlatformProvider 실제 구현체 (어댑터).
 *
 * <p>api 모듈은 workflow-core와 auth/베타 쿼터 서비스 모두에 의존하므로,
 * workflow-core의 포트(BetaPlatformProvider)를 {@code UserRepository}(betaAccess) +
 * {@code BetaQuotaService}(Redis 쿼터)로 연결한다.
 *
 * <p>{@code @ConditionalOnMissingBean}만으로는 서로 다른 모듈(api/workflow-core)에서
 * component-scan되는 plain {@code @Component} 두 개의 등록 순서가 보장되지 않아
 * {@code StubBetaPlatformProvider}가 이 빈을 가리거나(shadow) NoUniqueBeanDefinitionException으로
 * 기동이 실패할 위험이 있다. {@code @Primary}로 스캔 순서와 무관하게 항상 이 빈이 선택되도록 고정한다.
 * (workflow-core 단독 컨텍스트처럼 이 빈이 아예 없을 때만 Stub이 유일한 빈으로 사용된다.)
 */
@Component
@Primary
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
    public String reserveQuota(UUID userId) {
        return betaQuotaService.checkQuota(userId);
    }

    @Override
    public void recordTokens(UUID userId, long totalTokens) {
        betaQuotaService.addUsedTokens(userId, totalTokens);
    }

    @Override
    public void releaseDailyCall(String reservationKey) {
        betaQuotaService.releaseDailyCall(reservationKey);
    }
}
