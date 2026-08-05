package com.ieum.api.config;

import com.ieum.api.webhookcredential.domain.WebhookCredential;
import com.ieum.api.webhookcredential.domain.WebhookProvider;
import com.ieum.api.webhookcredential.repository.WebhookCredentialRepository;
import com.ieum.api.webhookcredential.service.WebhookCredentialService;
import com.ieum.workflowcore.engine.executor.WebhookCredentialProvider;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * WebhookCredentialProvider 실제 구현체 (어댑터).
 *
 * <p>api 모듈은 workflow-core와 webhookcredential 패키지 모두에 접근하므로, workflow-core의 포트를
 * 웹훅 자격증명 저장소와 연결한다. 이 빈이 등록되면 workflow-core의 StubWebhookCredentialProvider는
 * {@code @ConditionalOnMissingBean}에 의해 자동으로 비활성화된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultWebhookCredentialProvider implements WebhookCredentialProvider {

    private final WebhookCredentialRepository repository;
    private final WebhookCredentialService credentialService;

    @Override
    public Optional<String> resolveWebhookUrl(UUID credentialId, UUID userId) {
        if (credentialId == null || userId == null) {
            return Optional.empty();
        }

        // 소유자 검증은 이 조회 하나에 걸려 있다 — findByIdAndUserId를 findById로 바꾸면
        // 남의 크레덴셜 id를 config에 박아 넣는 것만으로 URL이 새어 나간다.
        WebhookCredential credential = repository.findByIdAndUserId(credentialId, userId).orElse(null);
        if (credential == null) {
            log.warn("[WebhookCredential] 자격증명이 없거나 권한이 없음 — credentialId: {}", credentialId);
            return Optional.empty();
        }
        if (!credential.isEnabled()) {
            log.warn("[WebhookCredential] 비활성 자격증명 건너뜀 — credentialId: {}", credentialId);
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(credentialService.decryptWebhookUrl(credential));
        } catch (Exception e) {
            log.warn("[WebhookCredential] 복호화 실패 — credentialId: {}, cause: {}",
                    credentialId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> resolveAlertWebhookUrl(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return repository.findByUserIdAndProviderAndAlertTargetTrue(userId, WebhookProvider.DISCORD)
                .stream()
                .filter(WebhookCredential::isEnabled)
                .findFirst()
                .flatMap(credential -> {
                    try {
                        return Optional.ofNullable(credentialService.decryptWebhookUrl(credential));
                    } catch (Exception e) {
                        log.warn("[AlertWebhook] 알림 대상 웹훅 URL 복호화 실패 — credentialId: {}",
                                credential.getId());
                        return Optional.empty();
                    }
                });
    }
}
