package com.ieum.api.config;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.common.util.AesEncryptor;
import com.ieum.workflowcore.engine.executor.NotionTokenProvider;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * NotionTokenProvider 실제 구현체 (어댑터).
 *
 * <p>api 모듈은 workflow-core와 auth 모두에 의존하므로,
 * workflow-core의 포트(NotionTokenProvider)를 auth의 리포지토리(ConnectedAccountRepository)와 연결한다.
 *
 * <p>이 빈이 등록되면 workflow-core의 {@code StubNotionTokenProvider}는
 * {@code @ConditionalOnMissingBean}에 의해 자동으로 비활성화된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultNotionTokenProvider implements NotionTokenProvider {

    private final ConnectedAccountRepository connectedAccountRepository;
    private final AesEncryptor aesEncryptor;

    @Override
    public Optional<String> getAccessToken(UUID userId) {
        return connectedAccountRepository
            .findByUserIdAndProvider(userId, AuthProvider.NOTION)
            .map(account -> {
                log.debug("[DefaultNotionTokenProvider] userId={} Notion token 조회", userId);
                return aesEncryptor.decrypt(account.getAccessToken());
            });
    }
}
