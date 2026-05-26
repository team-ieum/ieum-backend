package com.ieum.api.config;

import com.ieum.auth.service.GitHubTokenService;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.engine.executor.GitHubTokenProvider;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * GitHubTokenProvider 실제 구현체 (어댑터).
 *
 * <p>api 모듈은 workflow-core와 auth 모두에 의존하므로,
 * workflow-core의 포트(GitHubTokenProvider)를 auth의 GitHubTokenService와 연결한다.
 *
 * <p>이 빈이 등록되면 workflow-core의 {@code StubGitHubTokenProvider}는
 * {@code @ConditionalOnMissingBean}에 의해 자동으로 비활성화된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultGitHubTokenProvider implements GitHubTokenProvider {

    private final GitHubTokenService gitHubTokenService;

    @Override
    public Optional<String> getAccessToken(UUID userId) {
        try {
            String token = gitHubTokenService.getValidAccessToken(userId);
            return Optional.of(token);
        } catch (CustomException e) {
            if (e.getErrorCode() == ErrorCode.ACCOUNT_NOT_CONNECTED) {
                // User has not connected GitHub — return empty (not an error)
                log.debug("[DefaultGitHubTokenProvider] userId={} GitHub not connected", userId);
                return Optional.empty();
            }
            // AUTHENTICATION_REQUIRED or TOKEN_REFRESH_FAILED — propagate to caller
            throw e;
        }
    }
}
