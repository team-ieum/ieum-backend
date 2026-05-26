package com.ieum.auth.service;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptor;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * Google OAuth Access Token 유효성 검사 및 자동 갱신 서비스.
 *
 * <h3>갱신 판단 기준</h3>
 * <ol>
 *   <li>Refresh Token 만료 여부 선확인 → 만료 시 즉시 {@code AUTHENTICATION_REQUIRED} 예외</li>
 *   <li>{@code tokenExpiresAt} 있으면 5분 threshold 기반 판단 (Google API 호출 없음)</li>
 *   <li>{@code tokenExpiresAt} 없는 기존 레코드 → tokeninfo API 폴백</li>
 * </ol>
 *
 * <h3>Token Rotation</h3>
 * Google 갱신 응답에 새 {@code refresh_token}이 포함되면 DB에 교체 저장한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleTokenService {

    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

    /** Google Access Token 기본 유효 시간(초). expires_in 파싱 실패 시 폴백. */
    private static final int DEFAULT_EXPIRES_IN_SECONDS = 3600;

    private final ConnectedAccountRepository connectedAccountRepository;
    private final AesEncryptor aesEncryptor;
    private final RestTemplate restTemplate;

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret}")
    private String clientSecret;

    /**
     * userId에 해당하는 유효한 Google Access Token(평문)을 반환한다.
     *
     * <p>Access Token이 만료(또는 5분 이내 만료 예정)이면 자동 갱신 후 반환한다.</p>
     *
     * @param userId 조회할 사용자 ID
     * @return 유효한 Google Access Token (평문)
     * @throws CustomException ACCOUNT_NOT_CONNECTED   — Google 연동 계정 없음
     * @throws CustomException AUTHENTICATION_REQUIRED — Refresh Token 만료 또는 Google 갱신 거절
     * @throws CustomException TOKEN_REFRESH_FAILED    — 예기치 않은 네트워크/서버 오류
     */
    @Transactional
    public String getValidAccessToken(UUID userId) {
        ConnectedAccount account = connectedAccountRepository
            .findByUserIdAndProvider(userId, AuthProvider.GOOGLE)
            .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_CONNECTED));

        // 1. Refresh Token 자체 만료 선확인
        if (account.isRefreshTokenExpired()) {
            log.warn("[GoogleTokenService] userId={} refresh_token 만료 — 재인증 필요", userId);
            throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED);
        }

        String accessToken = aesEncryptor.decrypt(account.getAccessToken());

        // 2. Access Token 만료 여부 확인
        boolean needsRefresh = account.getTokenExpiresAt() != null
            ? account.isAccessTokenExpiringSoon()       // tokenExpiresAt 기반 (5분 threshold)
            : !isTokenValidViaApi(accessToken);          // 기존 레코드 폴백: tokeninfo API

        if (!needsRefresh) {
            return accessToken;
        }

        log.info("[GoogleTokenService] userId={} access_token 갱신 필요 — refresh 시도", userId);

        if (account.getRefreshToken() == null) {
            log.warn("[GoogleTokenService] userId={} refresh_token 없음 — 재인증 필요", userId);
            throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED);
        }

        String refreshTokenPlain = aesEncryptor.decrypt(account.getRefreshToken());
        RefreshResult result = callRefreshEndpoint(userId, refreshTokenPlain);

        // 3. JPA dirty checking — 트랜잭션 커밋 시 자동 UPDATE
        LocalDateTime newTokenExpiresAt = LocalDateTime.now().plusSeconds(result.expiresIn());
        String encryptedNewRefreshToken = result.refreshToken() != null
            ? aesEncryptor.encrypt(result.refreshToken())
            : null;

        account.updateTokens(
            aesEncryptor.encrypt(result.accessToken()),
            encryptedNewRefreshToken != null ? encryptedNewRefreshToken : account.getRefreshToken(),
            newTokenExpiresAt,
            account.getRefreshTokenExpiresAt()
        );

        log.info("[GoogleTokenService] userId={} access_token 갱신 완료 (rotation={})",
            userId, result.refreshToken() != null);

        return result.accessToken();
    }

    /**
     * tokenExpiresAt 없는 기존 레코드용 폴백.
     * Google tokeninfo API(POST)로 토큰 유효성을 확인한다.
     *
     * <p>POST 방식으로 호출하여 access_token이 서버 액세스 로그에 노출되지 않도록 한다.</p>
     */
    private boolean isTokenValidViaApi(String accessToken) {
        try {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("access_token", accessToken);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(TOKENINFO_URL, request, Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("[GoogleTokenService] tokeninfo API 호출 실패 — 만료로 간주: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Google token endpoint에 refresh_token으로 새 access_token을 요청한다.
     *
     * @return RefreshResult — 새 access_token, expires_in, rotation된 refresh_token(nullable)
     * @throws CustomException AUTHENTICATION_REQUIRED — invalid_grant 등 Google 거절
     * @throws CustomException TOKEN_REFRESH_FAILED    — 네트워크 오류 등 예기치 않은 실패
     */
    private RefreshResult callRefreshEndpoint(UUID userId, String refreshToken) {
        try {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("grant_type", "refresh_token");
            params.add("refresh_token", refreshToken);
            params.add("client_id", clientId);
            params.add("client_secret", clientSecret);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(TOKEN_ENDPOINT, request, Map.class);

            if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[GoogleTokenService] userId={} token endpoint 응답 실패: status={}", userId,
                    response != null ? response.getStatusCode() : "null");
                throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED);
            }

            Map<?, ?> body = response.getBody();

            Object newToken = body.get("access_token");
            if (newToken == null) {
                log.warn("[GoogleTokenService] userId={} 응답에 access_token 없음", userId);
                throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED);
            }

            int expiresIn = DEFAULT_EXPIRES_IN_SECONDS;
            if (body.get("expires_in") instanceof Number number) {
                expiresIn = number.intValue();
            }

            // Token Rotation: Google이 새 refresh_token을 내려주면 교체
            String newRefreshToken = null;
            if (body.get("refresh_token") instanceof String rotated) {
                newRefreshToken = rotated;
                log.info("[GoogleTokenService] userId={} refresh_token rotation 발생", userId);
            }

            return new RefreshResult(newToken.toString(), newRefreshToken, expiresIn);

        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[GoogleTokenService] userId={} 토큰 갱신 중 오류 발생", userId, e);
            throw new CustomException(ErrorCode.TOKEN_REFRESH_FAILED);
        }
    }

    /**
     * Google token endpoint 갱신 결과.
     *
     * @param accessToken  새 Access Token (평문)
     * @param refreshToken rotation된 새 Refresh Token (평문, null이면 기존 유지)
     * @param expiresIn    Access Token 유효 시간(초)
     */
    private record RefreshResult(String accessToken, String refreshToken, int expiresIn) {}
}
