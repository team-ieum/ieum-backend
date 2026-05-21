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
