package com.ieum.workflowcore.engine.executor;

import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * GitHubTokenProvider 임시 Stub 구현체.
 *
 * <p>api 모듈의 DefaultGitHubTokenProvider가 등록되면 자동으로 대체된다.
 * {@code @ConditionalOnMissingBean}으로 실제 구현체가 없을 때만 활성화된다.
 *
 * <p>GitHub 연동은 선택적이므로 예외 대신 Optional.empty()를 반환한다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(value = GitHubTokenProvider.class, ignored = StubGitHubTokenProvider.class)
public class StubGitHubTokenProvider implements GitHubTokenProvider {

    @Override
    public Optional<String> getAccessToken(UUID userId) {
        log.debug("[StubGitHubTokenProvider] GitHub token provider not configured — returning empty");
        return Optional.empty();
    }
}
