package com.ieum.api.config;

import com.ieum.api.webhookcredential.domain.WebhookCredential;
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
        log.info("[webhook-debug] resolveWebhookUrl 진입 — credentialId: {}, 실행 userId: {}", credentialId, userId);
        if (credentialId == null || userId == null) {
            log.warn("[webhook-debug] credentialId/userId가 null — credentialId: {}, userId: {}", credentialId, userId);
            return Optional.empty();
        }

        WebhookCredential credential = repository.findByIdAndUserId(credentialId, userId).orElse(null);
        if (credential == null) {
            log.warn("[webhook-debug] 웹훅 자격증명이 존재하지 않거나 권한이 없음 — credentialId: {}, userId: {}", credentialId, userId);
            return Optional.empty();
        }
        log.info("[webhook-debug] 자격증명 조회 성공 — credentialId: {}, provider: {}, enabled: {}",
                credentialId, credential.getProvider(), credential.isEnabled());
        if (!credential.isEnabled()) {
            log.warn("[webhook-debug] 비활성 웹훅 자격증명 건너뜀 — credentialId: {}", credentialId);
            return Optional.empty();
        }
        try {
            String url = credentialService.decryptWebhookUrl(credential);
            log.info("[webhook-debug] 복호화 성공 — credentialId: {}, url 길이: {}",
                    credentialId, url != null ? url.length() : 0);
            return Optional.ofNullable(url);
        } catch (Exception e) {
            log.warn("[webhook-debug] 복호화 실패 — credentialId: {}, error: {}", credentialId, e.getMessage());
            return Optional.empty();
        }
    }
}
