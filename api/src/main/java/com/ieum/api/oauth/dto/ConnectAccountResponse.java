package com.ieum.api.oauth.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Google 계정 연동 시작 응답.
 *
 * <p>{@code authorizationUrl}로 브라우저를 리다이렉트하면 Google 동의 화면을 거쳐
 * 현재 로그인된 계정에 Google이 연동된다.
 *
 * <pre>
 * { "authorizationUrl": "/api/v1/oauth2/authorize/google?scope_groups=gmail&link_token=..." }
 * </pre>
 */
@Getter
@Builder
public class ConnectAccountResponse {

    /** link_token이 포함된 Google OAuth 연동 URL. */
    private final String authorizationUrl;

    public static ConnectAccountResponse of(String authorizationUrl) {
        return ConnectAccountResponse.builder()
            .authorizationUrl(authorizationUrl)
            .build();
    }
}
