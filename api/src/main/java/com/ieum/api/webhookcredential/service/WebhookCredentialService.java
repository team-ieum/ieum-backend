package com.ieum.api.webhookcredential.service;

import com.ieum.api.webhookcredential.domain.WebhookCredential;
import com.ieum.api.webhookcredential.domain.WebhookProvider;
import com.ieum.api.webhookcredential.repository.WebhookCredentialRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptionService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WebhookCredentialService {

    private static final int MAX_WEBHOOKS_PER_USER = 20;

    private final WebhookCredentialRepository repository;
    private final AesEncryptionService aesEncryptionService;

    @Transactional
    public WebhookCredential create(UUID userId, WebhookProvider provider, String displayName,
                                    String webhookUrl, String defaultChannel) {
        if (repository.existsByUserIdAndDisplayName(userId, displayName)) {
            throw new CustomException(ErrorCode.WEBHOOK_CREDENTIAL_DUPLICATE_NAME);
        }
        if (repository.countByUserId(userId) >= MAX_WEBHOOKS_PER_USER) {
            throw new CustomException(ErrorCode.WEBHOOK_CREDENTIAL_LIMIT_EXCEEDED,
                    "웹훅 자격증명은 최대 " + MAX_WEBHOOKS_PER_USER + "개까지 등록할 수 있습니다.");
        }

        WebhookCredential credential = WebhookCredential.builder()
                .userId(userId)
                .provider(provider)
                .displayName(displayName)
                .encryptedWebhookUrl(aesEncryptionService.encrypt(webhookUrl))
                .defaultChannel(defaultChannel)
                .enabled(true)
                .build();

        return repository.save(credential);
    }

    public List<WebhookCredential> getByUserId(UUID userId) {
        return repository.findByUserId(userId);
    }

    public WebhookCredential getByIdAndUserId(UUID id, UUID userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.WEBHOOK_CREDENTIAL_NOT_FOUND));
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        WebhookCredential credential = getByIdAndUserId(id, userId);
        repository.delete(credential);
    }

    /**
     * 실행 주입용: 복호화된 webhook URL을 반환한다.
     * (workflow-core가 slack/discord 도구 실행 시 webhook_url로 주입)
     */
    public String decryptWebhookUrl(WebhookCredential credential) {
        return aesEncryptionService.decrypt(credential.getEncryptedWebhookUrl());
    }
}
