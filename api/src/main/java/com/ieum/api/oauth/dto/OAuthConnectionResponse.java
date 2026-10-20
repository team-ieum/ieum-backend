package com.ieum.api.oauth.dto;

import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.domain.SlackInstallation;
import com.ieum.auth.domain.SlackUserLink;
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

    @Schema(description = "연동 계정 ID (SLACK은 Slack 링크 ID)", example = "550e8400-e29b-41d4-a716-446655440000")
    private final UUID id;

    @Schema(description = "OAuth 제공자 (GOOGLE, GITHUB, NOTION, SLACK)", example = "GOOGLE")
    private final String provider;

    @Schema(description = "제공자 계정 식별자 (SLACK은 워크스페이스 team id)", example = "1029384756")
    private final String providerAccountId;

    @Schema(description = "표시 이름 — SLACK은 워크스페이스 이름, 다른 제공자는 null", example = "이음 팀", nullable = true)
    private final String displayName;

    @Schema(description = "부여된 Scopes 목록")
    private final List<String> scopes;

    @Schema(description = "연동 일시")
    private final LocalDateTime createdAt;

    public static OAuthConnectionResponse from(ConnectedAccount account) {
        return OAuthConnectionResponse.builder()
            .id(account.getId())
            .provider(account.getProvider().name())
            .providerAccountId(account.getProviderAccountId())
            .scopes(parseScopes(account.getScopes()))
            .createdAt(account.getCreatedAt())
            .build();
    }

    public static OAuthConnectionResponse fromSlack(SlackUserLink link) {
        SlackInstallation installation = link.getInstallation();
        return OAuthConnectionResponse.builder()
            .id(link.getId())
            .provider("SLACK")
            .providerAccountId(installation.getTeamId())
            .displayName(installation.getTeamName())
            .scopes(parseScopes(installation.getScopes()))
            .createdAt(link.getCreatedAt())
            .build();
    }

    private static List<String> parseScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(scopes.split("[,\\s]+"))
            .filter(s -> !s.isBlank())
            .toList();
    }
}
