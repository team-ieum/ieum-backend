package com.ieum.auth.service;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
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

/**
 * GitHub App Access Token 유효성 검사 및 자동 갱신 서비스.
 *
 * <p>흐름:</p>
 * <ol>
 *   <li>{@code connected_accounts}에서 AES 복호화된 access_token을 가져온다.</li>
 *   <li>{@code tokenExpiresAt}으로 만료 여부를 확인한다 (네트워크 호출 없음).</li>
 *   <li>만료된 경우 refresh_token으로 새 access_token/refresh_token을 발급받아 DB에 업데이트한다.</li>
 *   <li>refresh_token도 만료됐으면 {@code AUTHENTICATION_REQUIRED} 예외를 던진다.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GitHubTokenService {

    private final ConnectedAccountRepository connectedAccountRepository;
    private final AesEncryptor aesEncryptor;
    private final RestTemplate restTemplate;

    @Value("${github.app.token-url}")
    private String githubTokenUrl;

    @Value("${github.app.client-id}")
    private String clientId;

    @Value("${github.app.client-secret}")
    private String clientSecret;

    /**
     * userId에 해당하는 유효한 GitHub Access Token(평문)을 반환한다.
     *
     * <p>토큰이 만료된 경우 자동으로 갱신하고 DB에 암호화된 값을 업데이트한다.</p>
     *
     * @param userId 조회할 사용자 ID
     * @return 유효한 GitHub Access Token (평문)
     * @throws CustomException ACCOUNT_NOT_CONNECTED — GitHub 연동 계정이 없는 경우
     * @throws CustomException AUTHENTICATION_REQUIRED — refresh_token 만료 또는 갱신 거절
     * @throws CustomException TOKEN_REFRESH_FAILED — GitHub API 호출 중 예기치 않은 오류
     */
    @Transactional
    public String getValidAccessToken(UUID userId) {
        ConnectedAccount account = connectedAccountRepository
            .findByUserIdAndProvider(userId, AuthProvider.GITHUB)
            .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_CONNECTED));

        // GitHub App 토큰은 항상 만료 시각이 있으나, null이면 만료로 간주한다.
        boolean accessTokenExpired = account.getTokenExpiresAt() == null
            || account.isAccessTokenExpiringSoon();

        if (!accessTokenExpired) {
            return aesEncryptor.decrypt(account.getAccessToken());
        }

        log.info("[GitHubTokenService] userId={} access_token expired — attempting refresh", userId);

        // GitHub App refresh_token도 항상 만료 시각이 있으나, null이면 만료로 간주한다.
        boolean refreshTokenExpired = account.getRefreshTokenExpiresAt() == null
            || account.isRefreshTokenExpired();
        if (refreshTokenExpired) {
            log.warn("[GitHubTokenService] userId={} refresh_token expired — re-auth required", userId);
            throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED);
        }

        String accessToken = aesEncryptor.decrypt(account.getAccessToken());

        String refreshTokenPlain = aesEncryptor.decrypt(account.getRefreshToken());
        Map<String, Object> tokenResponse = callRefreshEndpoint(userId, refreshTokenPlain);

        String newAccessToken = extractRequired(tokenResponse, "access_token", userId);
        String newRefreshToken = extractRequired(tokenResponse, "refresh_token", userId);
        long expiresIn = toLong(tokenResponse.get("expires_in"), 28800L);
        long refreshExpiresIn = toLong(tokenResponse.get("refresh_token_expires_in"), 15897600L);

        LocalDateTime tokenExpiresAt = LocalDateTime.now().plusSeconds(expiresIn);
        LocalDateTime refreshTokenExpiresAt = LocalDateTime.now().plusSeconds(refreshExpiresIn);

        // JPA dirty checking으로 트랜잭션 커밋 시 자동 UPDATE
        account.updateTokens(
            aesEncryptor.encrypt(newAccessToken),
            aesEncryptor.encrypt(newRefreshToken),
            tokenExpiresAt,
            refreshTokenExpiresAt
        );
        log.info("[GitHubTokenService] userId={} tokens refreshed", userId);

        return newAccessToken;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callRefreshEndpoint(UUID userId, String refreshToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            Map<String, String> body = Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "grant_type", "refresh_token",
                "refresh_token", refreshToken
            );

            Map<String, Object> response = restTemplate.postForObject(
                githubTokenUrl,
                new HttpEntity<>(body, headers),
                Map.class
            );

            if (response == null) {
                log.warn("[GitHubTokenService] userId={} token endpoint 응답 없음", userId);
                throw new CustomException(ErrorCode.TOKEN_REFRESH_FAILED);
            }
            if (response.containsKey("error")) {
                log.warn("[GitHubTokenService] userId={} refresh 거절 — {}: {}",
                    userId, response.get("error"), response.get("error_description"));
                throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED);
            }
            return response;

        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[GitHubTokenService] userId={} token refresh 중 오류 발생", userId, e);
            throw new CustomException(ErrorCode.TOKEN_REFRESH_FAILED);
        }
    }

    private String extractRequired(Map<String, Object> response, String key, UUID userId) {
        String value = Optional.ofNullable(response.get(key))
            .map(Object::toString)
            .orElse("");
        if (value.isEmpty()) {
            log.error("[GitHubTokenService] missing '{}' in response — userId={}", key, userId);
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
