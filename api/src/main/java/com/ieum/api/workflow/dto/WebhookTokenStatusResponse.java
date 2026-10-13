package com.ieum.api.workflow.dto;

import java.time.LocalDateTime;
import java.util.Optional;

/** 발급 여부만 — 평문·해시는 내보내지 않는다. */
public record WebhookTokenStatusResponse(boolean issued, LocalDateTime issuedAt) {

    public static WebhookTokenStatusResponse from(Optional<LocalDateTime> issuedAt) {
        return new WebhookTokenStatusResponse(issuedAt.isPresent(), issuedAt.orElse(null));
    }
}
