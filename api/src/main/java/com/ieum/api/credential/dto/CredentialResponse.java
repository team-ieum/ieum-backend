package com.ieum.api.credential.dto;

import com.ieum.api.credential.domain.Credential;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "크레덴셜 응답")
@Getter
@Builder
public class CredentialResponse {

    @Schema(description = "크레덴셜 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "AI 프로바이더", example = "CLAUDE")
    private String provider;

    @Schema(description = "크레덴셜 타입", example = "API_KEY")
    private String credentialType;

    @Schema(description = "표시 이름", example = "내 Claude API Key")
    private String displayName;

    @Schema(description = "API 키 힌트 (마스킹)", example = "sk-ant...ab12")
    private String keyHint;

    @Schema(description = "유효 여부", example = "true")
    private boolean isValid;

    @Schema(description = "마지막 검증 일시", example = "2026-03-19T10:00:00")
    private LocalDateTime lastValidatedAt;

    @Schema(description = "등록 일시", example = "2026-03-19T09:00:00")
    private LocalDateTime createdAt;

    public static CredentialResponse from(Credential credential) {
        return CredentialResponse.builder()
                .id(credential.getId())
                .provider(credential.getProvider().name())
                .credentialType(credential.getCredentialType().name())
                .displayName(credential.getDisplayName())
                .keyHint(credential.getKeyHint())
                .isValid(credential.isValid())
                .lastValidatedAt(credential.getLastValidatedAt())
                .createdAt(credential.getCreatedAt())
                .build();
    }
}
