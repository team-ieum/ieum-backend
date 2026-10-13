package com.ieum.api.workflow.dto;

import com.ieum.workflowcore.service.WorkflowWebhookTokenService.IssuedToken;
import java.time.LocalDateTime;

/** 발급 응답 — 평문 토큰이 나가는 유일한 자리다. 다시 조회할 수 없다. */
public record WebhookTokenResponse(String token, String path, LocalDateTime issuedAt) {

    public static WebhookTokenResponse from(IssuedToken issued) {
        return new WebhookTokenResponse(issued.token(), "/api/v1/hooks/" + issued.token(), issued.issuedAt());
    }
}
