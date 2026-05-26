package com.ieum.api.oauth.service;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.domain.GitHubOAuthState;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.auth.repository.GitHubOAuthStateRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GitHubOAuthService {

    private static final String GITHUB_AUTH_URL = "https://github.com/login/oauth/authorize";
    private static final long OAUTH_STATE_TTL_SECONDS = 300L;

    private final ConnectedAccountRepository connectedAccountRepository;
    private final GitHubOAuthStateRepository gitHubOAuthStateRepository;
    private final AesEncryptor aesEncryptor;
    private final RestTemplate restTemplate;

    @Value("${github.app.token-url}")
    private String githubTokenUrl;

    @Value("${github.app.client-id}")
    private String clientId;

    @Value("${github.app.client-secret}")
    private String clientSecret;

    @Value("${github.app.redirect-uri}")
    private String githubRedirectUri;

    public String generateAuthUrl(UUID userId) {
        String state = UUID.randomUUID().toString();
        gitHubOAuthStateRepository.save(
            GitHubOAuthState.builder()
                .state(state)
                .userId(userId)
                .ttl(OAUTH_STATE_TTL_SECONDS)
                .build()
        );

        return UriComponentsBuilder
            .fromUriString(GITHUB_AUTH_URL)
            .queryParam("client_id", clientId)
            .queryParam("redirect_uri", githubRedirectUri)
            .queryParam("state", state)
            .build().toUriString();
    }

    @Transactional
    public void handleCallback(String code, UUID userId) {
        Map<String, Object> tokenResponse = exchangeCodeForTokens(code);

        String accessToken = extractRequired(tokenResponse, "access_token", userId);
        String refreshToken = extractRequired(tokenResponse, "refresh_token", userId);
        long expiresIn = toLong(tokenResponse.get("expires_in"), 28800L);
        long refreshExpiresIn = toLong(tokenResponse.get("refresh_token_expires_in"), 15897600L);
        String scope = Optional.ofNullable(tokenResponse.get("scope")).map(Object::toString).orElse(null);

        LocalDateTime tokenExpiresAt = LocalDateTime.now().plusSeconds(expiresIn);
        LocalDateTime refreshTokenExpiresAt = LocalDateTime.now().plusSeconds(refreshExpiresIn);

        String encryptedAccess = aesEncryptor.encrypt(accessToken);
        String encryptedRefresh = aesEncryptor.encrypt(refreshToken);

        connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GITHUB)
            .ifPresentOrElse(
                account -> {
                    account.updateTokens(encryptedAccess, encryptedRefresh,
                        tokenExpiresAt, refreshTokenExpiresAt);
                    account.updateScopes(scope);
                    log.info("[GitHubOAuthService] userId={} GitHub tokens refreshed", userId);
                },
                () -> {
                    connectedAccountRepository.save(
                        ConnectedAccount.builder()
                            .userId(userId)
                            .provider(AuthProvider.GITHUB)
                            .accessToken(encryptedAccess)
                            .refreshToken(encryptedRefresh)
                            .tokenExpiresAt(tokenExpiresAt)
                            .refreshTokenExpiresAt(refreshTokenExpiresAt)
                            .scopes(scope)
                            .build()
                    );
                    log.info("[GitHubOAuthService] userId={} GitHub connected_account created", userId);
                }
            );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeCodeForTokens(String code) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            Map<String, String> body = Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "redirect_uri", githubRedirectUri
            );

            Map<String, Object> response = restTemplate.postForObject(
                githubTokenUrl,
                new HttpEntity<>(body, headers),
                Map.class
            );

            if (response == null) {
                throw new CustomException(ErrorCode.TOKEN_REFRESH_FAILED);
            }
            if (response.containsKey("error")) {
                log.error("[GitHubOAuthService] token exchange error — {}: {}",
                    response.get("error"), response.get("error_description"));
                throw new CustomException(ErrorCode.TOKEN_REFRESH_FAILED);
            }
            return response;

        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[GitHubOAuthService] token exchange failed", e);
            throw new CustomException(ErrorCode.TOKEN_REFRESH_FAILED);
        }
    }

    private String extractRequired(Map<String, Object> response, String key, UUID userId) {
        String value = Optional.ofNullable(response.get(key))
            .map(Object::toString)
            .orElse("");
        if (value.isEmpty()) {
            log.error("[GitHubOAuthService] missing '{}' in response — userId={}", key, userId);
            throw new CustomException(ErrorCode.TOKEN_REFRESH_FAILED);
        }
        return value;
    }

    private long toLong(Object value, long defaultValue) {
        return Optional.ofNullable(value)
            .map(v -> {
                try {
                    return Long.parseLong(v.toString());
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            })
            .orElse(defaultValue);
    }
}
