package com.ieum.api.config;

import com.ieum.auth.service.GoogleTokenService;
import com.ieum.workflowcore.engine.executor.GoogleTokenProvider;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * GoogleTokenProvider 실제 구현체 (어댑터).
 *
 * <p>api 모듈은 workflow-core와 auth 모두에 의존하므로,
 * workflow-core의 포트(GoogleTokenProvider)를 auth의 서비스(GoogleTokenService)로 연결한다.
 *
 * <p>이 빈이 등록되면 workflow-core의 {@code StubGoogleTokenProvider}는
 * {@code @ConditionalOnMissingBean}에 의해 자동으로 비활성화된다.
 */
@Component
@RequiredArgsConstructor
public class DefaultGoogleTokenProvider implements GoogleTokenProvider {

    private final GoogleTokenService googleTokenService;

    @Override
    public String getValidAccessToken(UUID userId) {
        return googleTokenService.getValidAccessToken(userId);
    }
}
