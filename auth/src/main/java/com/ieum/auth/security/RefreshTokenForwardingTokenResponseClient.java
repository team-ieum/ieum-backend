package com.ieum.auth.security;

import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

/**
 * 토큰 응답의 refresh_token을 additionalParameters에 실어 {@link CustomOAuth2UserService}까지 보낸다 (IEUM-BE-74).
 *
 * <p>Spring은 refresh_token을 additionalParameters에서 빼고, {@link OAuth2UserRequest}에는 refresh token 자리가 없다.
 * additionalParameters는 {@code OAuth2LoginAuthenticationProvider}가 {@link OAuth2UserRequest}로 그대로 넘긴다.
 */
@RequiredArgsConstructor
public class RefreshTokenForwardingTokenResponseClient
    implements OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> {

    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> delegate;

    @Override
    public OAuth2AccessTokenResponse getTokenResponse(OAuth2AuthorizationCodeGrantRequest request) {
        OAuth2AccessTokenResponse response = delegate.getTokenResponse(request);
        if (response.getRefreshToken() == null) {
            return response;
        }
        Map<String, Object> parameters = new HashMap<>(response.getAdditionalParameters());
        parameters.put(OAuth2ParameterNames.REFRESH_TOKEN, response.getRefreshToken().getTokenValue());
        return OAuth2AccessTokenResponse.withResponse(response).additionalParameters(parameters).build();
    }
}
