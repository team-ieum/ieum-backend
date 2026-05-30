package com.ieum.api.webhookcredential.dto;

import com.ieum.api.webhookcredential.domain.WebhookProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateWebhookCredentialRequest(
        @NotNull(message = "provider는 필수입니다.")
        WebhookProvider provider,

        @NotBlank(message = "displayName은 필수입니다.")
        @Size(max = 100, message = "displayName은 100자 이하여야 합니다.")
        String displayName,

        @NotBlank(message = "webhookUrl은 필수입니다.")
        String webhookUrl,

        /** Slack 등에서 기본 채널을 덮어쓸 때 사용하는 선택 값. */
        @Size(max = 200, message = "defaultChannel은 200자 이하여야 합니다.")
        String defaultChannel
) {
}
