package com.ieum.api.webhookcredential.service;

import com.ieum.api.webhookcredential.domain.WebhookCredential;
import com.ieum.api.webhookcredential.domain.WebhookProvider;
import com.ieum.api.webhookcredential.repository.WebhookCredentialRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptionService;
import com.ieum.workflowcore.util.SensitiveDataMasker;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WebhookCredentialService {

    private static final int MAX_WEBHOOKS_PER_USER = 20;

    private final WebhookCredentialRepository repository;
    private final AesEncryptionService aesEncryptionService;

    /**
     * 웹훅 자격증명을 등록한다.
     *
     * <p>URL이 선언한 provider의 웹훅 호스트인지 먼저 검증한다 (IEUM-BE-62). 노드 config에는
     * 원문 웹훅 URL을 저장할 수 없고({@code RawWebhookUrlGuard}) 대신 여기 등록한 뒤
     * {@code config.webhookCredentialId}로 참조하는데, 등록이 무검증이면 임의 URL을
     * "Slack 웹훅"으로 올린 뒤 그 참조로 {@code HttpNodeExecutor}가 호출하게 만들 수 있다.
     *
     * <p>검증은 신규 등록에만 걸린다 — 이미 저장된 레코드의 조회·사용·삭제는 이 경로를 타지 않는다.
     */
    @Transactional
    public WebhookCredential create(UUID userId, WebhookProvider provider, String displayName,
                                    String rawWebhookUrl, String defaultChannel) {
        // 복사·붙여넣기로 딸려 온 앞뒤 공백은 떼고 검증·저장한다. 저장값이 곧 호출 대상 URL이다.
        String webhookUrl = rawWebhookUrl == null ? "" : rawWebhookUrl.strip();
        validateWebhookUrl(provider, webhookUrl);

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

    /**
     * URL이 provider의 웹훅 형식인지 검증한다. 판정은 {@link SensitiveDataMasker}에 맡긴다 —
     * 마스킹·노드 config 거부와 도메인 조각을 공유해야 한 쪽만 고쳐지는 일이 없다.
     *
     * <p>예외에 URL을 싣지 않는다: {@code GlobalExceptionHandler}가 예외 메시지를 그대로 WARN 로그에
     * 남기므로 여기 담는 값이 곧 로그에 남는데, 웹훅 URL은 그 자체가 비밀이다.
     */
    private void validateWebhookUrl(WebhookProvider provider, String webhookUrl) {
        boolean matched = switch (provider) {
            case SLACK -> SensitiveDataMasker.isSlackWebhookUrl(webhookUrl);
            case DISCORD -> SensitiveDataMasker.isDiscordWebhookUrl(webhookUrl);
        };
        if (!matched) {
            throw new CustomException(ErrorCode.WEBHOOK_CREDENTIAL_INVALID_URL);
        }
    }

    public List<WebhookCredential> getByUserId(UUID userId) {
        return repository.findByUserId(userId);
    }

    public WebhookCredential getByIdAndUserId(UUID id, UUID userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.WEBHOOK_CREDENTIAL_NOT_FOUND));
    }

    /**
     * 실행 실패 알림 대상을 지정하거나 해제한다.
     *
     * <p>지정 시 같은 사용자의 같은 provider 기존 대상을 먼저 내린다 — provider별로 하나만 유지된다.
     *
     * @throws CustomException WEBHOOK_CREDENTIAL_NOT_ALERTABLE — DISCORD가 아닌 크레덴셜을 지정한 경우
     */
    @Transactional
    public WebhookCredential setAlertTarget(UUID id, UUID userId, boolean alertTarget) {
        WebhookCredential credential = getByIdAndUserId(id, userId);
        if (alertTarget && credential.getProvider() != WebhookProvider.DISCORD) {
            throw new CustomException(ErrorCode.WEBHOOK_CREDENTIAL_NOT_ALERTABLE);
        }
        if (alertTarget) {
            repository.findByUserIdAndProviderAndAlertTargetTrue(userId, credential.getProvider())
                    .forEach(existing -> existing.changeAlertTarget(false));
        }
        credential.changeAlertTarget(alertTarget);
        return credential;
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
        try {
            return aesEncryptionService.decrypt(credential.getEncryptedWebhookUrl());
        } catch (Exception e) {
            log.error("[WebhookCredentialService] 웹훅 URL 복호화 실패 — credentialId: {}", credential.getId(), e);
            throw new CustomException(ErrorCode.CREDENTIAL_DECRYPT_FAILED);
        }
    }
}
