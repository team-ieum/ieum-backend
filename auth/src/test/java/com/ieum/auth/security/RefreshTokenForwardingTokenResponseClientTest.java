package com.ieum.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

class RefreshTokenForwardingTokenResponseClientTest {

    @SuppressWarnings("unchecked")
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> delegate =
        mock(OAuth2AccessTokenResponseClient.class);

    private final RefreshTokenForwardingTokenResponseClient client =
        new RefreshTokenForwardingTokenResponseClient(delegate);

    @Test
    @DisplayName("refresh_token을 additionalParameters에 실어 보낸다 — 기존 파라미터·토큰은 그대로")
    void forwardsRefreshToken() {
        given(delegate.getTokenResponse(any())).willReturn(OAuth2AccessTokenResponse.withToken("at")
            .tokenType(OAuth2AccessToken.TokenType.BEARER)
            .expiresIn(3599)
            .refreshToken("rt")
            .additionalParameters(Map.of("id_token", "x"))
            .build());

        OAuth2AccessTokenResponse response = client.getTokenResponse(null);

        assertThat(response.getAdditionalParameters())
            .containsEntry(OAuth2ParameterNames.REFRESH_TOKEN, "rt")
            .containsEntry("id_token", "x");
        assertThat(response.getAccessToken().getTokenValue()).isEqualTo("at");
        assertThat(response.getRefreshToken().getTokenValue()).isEqualTo("rt");
    }

    @Test
    @DisplayName("refresh_token이 없으면 응답을 그대로 돌려준다")
    void passesThroughWithoutRefreshToken() {
        OAuth2AccessTokenResponse original = OAuth2AccessTokenResponse.withToken("at")
            .tokenType(OAuth2AccessToken.TokenType.BEARER)
            .build();
        given(delegate.getTokenResponse(any())).willReturn(original);

        assertThat(client.getTokenResponse(null)).isSameAs(original);
    }
}
