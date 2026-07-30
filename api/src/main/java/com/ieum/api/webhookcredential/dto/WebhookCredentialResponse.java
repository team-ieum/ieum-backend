package com.ieum.api.webhookcredential.dto;

import com.ieum.api.webhookcredential.domain.WebhookCredential;
import com.ieum.api.webhookcredential.domain.WebhookProvider;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 웹훅 자격증명 응답 DTO.
 *
 * <p>보안상 webhook URL은 절대 응답에 포함하지 않는다(노출 시 누구나 발송 가능).
 */
public record WebhookCredentialResponse(
        UUID id,
        WebhookProvider provider,
        String displayName,
        String defaultChannel,
        boolean enabled,
        boolean alertTarget,
        LocalDateTime createdAt
) {
    public static WebhookCredentialResponse from(WebhookCredential credential) {
        return new WebhookCredentialResponse(
                credential.getId(),
                credential.getProvider(),
                credential.getDisplayName(),
                credential.getDefaultChannel(),
                credential.isEnabled(),
                credential.isAlertTarget(),
                credential.getCreatedAt()
        );
    }
}
