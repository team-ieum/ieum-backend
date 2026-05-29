package com.ieum.api.oauth.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 추가 scope 권한 요청 결과.
 *
 * <p>{@code missingScopes}가 비어있으면 이미 모든 권한을 보유하고 있어 재인증이 불필요하다.
 * {@code authorizationUrl}이 null이 아니면 해당 URL로 리다이렉트하여 Google 동의 화면을 진행한다.
 *
 * <pre>
 * // 이미 모든 scope 보유
 * { "missingScopes": [], "authorizationUrl": null, "requiresAuth": false }
 *
 * // 재인증 필요
 * { "missingScopes": ["https://..."], "authorizationUrl": "/api/v1/oauth2/authorize/google", "requiresAuth": true }
 * </pre>
 */
@Getter
@Builder
public class ScopeRequestResponse {

    /** 아직 보유하지 않은 scope URL 목록. */
    private final List<String> missingScopes;

    /** Google OAuth 재인증 URL. 재인증 불필요 시 null. */
    private final String authorizationUrl;

    /** 재인증 필요 여부. */
    private final boolean requiresAuth;

    public static ScopeRequestResponse noAuthRequired() {
        return ScopeRequestResponse.builder()
            .missingScopes(List.of())
            .authorizationUrl(null)
            .requiresAuth(false)
            .build();
    }

    public static ScopeRequestResponse authRequired(List<String> missingScopes, String authorizationUrl) {
        return ScopeRequestResponse.builder()
            .missingScopes(missingScopes)
            .authorizationUrl(authorizationUrl)
            .requiresAuth(true)
            .build();
    }
}
