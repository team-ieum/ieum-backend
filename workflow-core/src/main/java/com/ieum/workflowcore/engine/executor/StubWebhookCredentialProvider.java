package com.ieum.workflowcore.engine.executor;

import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * WebhookCredentialProvider 임시 Stub 구현체.
 *
 * <p>api 모듈의 DefaultWebhookCredentialProvider가 등록되면 {@code @ConditionalOnMissingBean}으로
 * 자동 대체된다. workflow-core 단독 실행(테스트, 모듈 빌드 등) 시에도 빈 주입 오류 없이
 * 기동할 수 있도록 빈 Optional을 반환한다(webhook 미주입).
 */
@Slf4j
@Component
@ConditionalOnMissingBean(value = WebhookCredentialProvider.class, ignored = StubWebhookCredentialProvider.class)
public class StubWebhookCredentialProvider implements WebhookCredentialProvider {

    @Override
    public Optional<String> resolveWebhookUrl(UUID credentialId, UUID userId) {
        if (credentialId != null) {
            log.warn("[StubWebhookCredentialProvider] DefaultWebhookCredentialProvider 미구현 — webhook 미주입. credentialId: {}", credentialId);
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> resolveAlertWebhookUrl(UUID userId) {
        return Optional.empty();
    }
}
