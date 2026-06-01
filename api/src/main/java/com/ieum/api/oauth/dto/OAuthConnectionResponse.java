package com.ieum.api.oauth.dto;

import com.ieum.auth.domain.ConnectedAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "OAuth 연동 계정 정보")
@Getter
@Builder
public class OAuthConnectionResponse {

    @Schema(description = "연동 계정 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private final UUID id;

    @Schema(description = "OAuth 제공자 (GOOGLE, GITHUB, NOTION)", example = "GOOGLE")
    private final String provider;

    @Schema(description = "제공자 계정 식별자", example = "1029384756")
    private final String providerAccountId;

    @Schema(description = "부여된 Scopes 목록")
    private final List<String> scopes;

    @Schema(description = "연동 일시")
    private final LocalDateTime createdAt;

    public static OAuthConnectionResponse from(ConnectedAccount account) {
        List<String> parsedScopes = Collections.emptyList();
        if (account.getScopes() != null && !account.getScopes().isBlank()) {
            parsedScopes = Arrays.stream(account.getScopes().split("[,\\s]+"))
                .filter(s -> !s.isBlank())
                .toList();
        }

        return OAuthConnectionResponse.builder()
            .id(account.getId())
            .provider(account.getProvider().name())
            .providerAccountId(account.getProviderAccountId())
            .scopes(parsedScopes)
            .createdAt(account.getCreatedAt())
            .build();
    }
}
