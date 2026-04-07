package com.ieum.auth.security;

import com.ieum.auth.domain.OAuthAuthorizationCode;
import com.ieum.auth.domain.RefreshToken;
import com.ieum.auth.domain.User;
import com.ieum.auth.jwt.JwtTokenProvider;
import com.ieum.auth.repository.OAuthAuthorizationCodeRepository;
import com.ieum.auth.repository.RefreshTokenRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final long OAUTH_CODE_TTL_SECONDS = 30L;

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OAuthAuthorizationCodeRepository oAuthAuthorizationCodeRepository;

    @Value("${oauth2.redirect-uri}")
    private String redirectUri;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
        Authentication authentication) throws IOException {
        CustomOAuth2User oAuth2User = (CustomOAuth2User) authentication.getPrincipal();
        User user = oAuth2User.getUser();

        String accessToken = jwtTokenProvider.generateAccessToken(
            user.getId(), user.getEmail(), user.getRole().name());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        refreshTokenRepository.save(RefreshToken.builder()
            .id(user.getId().toString())
            .token(refreshToken)
            .ttl(refreshExpiration / 1000)
            .build());

        long expiresIn = jwtTokenProvider.getAccessTokenExpiration();

        String code = UUID.randomUUID().toString();
        oAuthAuthorizationCodeRepository.save(OAuthAuthorizationCode.builder()
            .code(code)
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .expiresIn(expiresIn)
            .ttl(OAUTH_CODE_TTL_SECONDS)
            .build());

        String targetUrl = UriComponentsBuilder.fromUriString(redirectUri)
            .queryParam("code", code)
            .build().toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}