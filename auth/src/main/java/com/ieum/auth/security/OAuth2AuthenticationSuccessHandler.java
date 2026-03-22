package com.ieum.auth.security;

import com.ieum.auth.domain.RefreshToken;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.User;
import com.ieum.auth.jwt.JwtTokenProvider;
import com.ieum.auth.repository.RefreshTokenRepository;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${oauth2.redirect-uri}")
    private String redirectUri;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
        Authentication authentication) throws IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String providerId = oAuth2User.getAttribute("sub");

        User user = userRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, providerId)
            .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        String accessToken = jwtTokenProvider.generateAccessToken(
            user.getId(), user.getEmail(), user.getRole().name());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        refreshTokenRepository.save(RefreshToken.builder()
            .id(user.getId().toString())
            .token(refreshToken)
            .ttl(refreshExpiration / 1000)
            .build());

        long expiresIn = jwtTokenProvider.getExpiration(accessToken) / 1000;

        String targetUrl = UriComponentsBuilder.fromUriString(redirectUri)
            .queryParam("access_token", accessToken)
            .queryParam("refresh_token", refreshToken)
            .queryParam("expires_in", expiresIn)
            .build().toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
