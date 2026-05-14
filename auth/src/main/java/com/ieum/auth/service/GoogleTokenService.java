package com.ieum.auth.service;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptor;
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
 * <p>흐름:</p>
 * <ol>
 *   <li>{@code connected_accounts}에서 AES 복호화된 access_token을 가져온다.</li>
 *   <li>Google tokeninfo API로 토큰 유효성을 확인한다.</li>
 *   <li>만료된 경우 refresh_token으로 새 access_token을 발급받아 DB에 업데이트한다.</li>
 *   <li>갱신 불가(refresh_token 없음 또는 구글 거절)면 {@code AUTHENTICATION_REQUIRED} 예외를 던진다.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleTokenService {

    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

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
     * <p>토큰이 만료된 경우 자동으로 갱신하고 DB에 암호화된 값을 업데이트한다.</p>
     *
     * @param userId 조회할 사용자 ID
     * @return 유효한 Google Access Token (평문)
     * @throws CustomException ACCOUNT_NOT_CONNECTED — Google 연동 계정이 없는 경우
     * @throws CustomException AUTHENTICATION_REQUIRED — refresh_token 없음 또는 갱신 거절
     * @throws CustomException TOKEN_REFRESH_FAILED — Google API 호출 중 예기치 않은 오류
     */
    @Transactional
    public String getValidAccessToken(UUID userId) {
        ConnectedAccount account = connectedAccountRepository
            .findByUserIdAndProvider(userId, AuthProvider.GOOGLE)
            .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_CONNECTED));

        String accessToken = aesEncryptor.decrypt(account.getAccessToken());

        if (isTokenValid(accessToken)) {
            return accessToken;
        }

        log.info("[GoogleTokenService] userId={} access_token 만료 — refresh 시도", userId);

        if (account.getRefreshToken() == null) {
            log.warn("[GoogleTokenService] userId={} refresh_token 없음 — 재인증 필요", userId);
            throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED);
        }

        String refreshTokenPlain = aesEncryptor.decrypt(account.getRefreshToken());
        String newAccessToken = callRefreshEndpoint(userId, refreshTokenPlain);

        // JPA dirty checking으로 트랜잭션 커밋 시 자동 UPDATE
        account.updateAccessToken(aesEncryptor.encrypt(newAccessToken));
        log.info("[GoogleTokenService] userId={} access_token 갱신 완료", userId);

        return newAccessToken;
    }

    /**
     * Google tokeninfo API로 토큰 유효성을 확인한다.
     *
     * <p>POST 방식으로 호출하여 access_token이 URL(서버 액세스 로그)에 노출되지 않도록 한다.
     * 네트워크 오류 또는 4xx 응답 시 만료로 간주하여 {@code false}를 반환한다.</p>
     */
    private boolean isTokenValid(String accessToken) {
        try {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("access_token", accessToken);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(TOKENINFO_URL, request, Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("[GoogleTokenService] tokeninfo 호출 실패 — 만료로 간주: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Google token endpoint에 refresh_token으로 새 access_token을 요청한다.
     *
     * @throws CustomException AUTHENTICATION_REQUIRED — Google이 refresh를 거절한 경우 (invalid_grant 등)
     * @throws CustomException TOKEN_REFRESH_FAILED — 네트워크 오류 등 예기치 않은 실패
     */
    private String callRefreshEndpoint(UUID userId, String refreshToken) {
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
                    response.getStatusCode());
                throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED);
            }

            Object newToken = response.getBody().get("access_token");
            if (newToken == null) {
                log.warn("[GoogleTokenService] userId={} 응답에 access_token 없음", userId);
                throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED);
            }

            return newToken.toString();

        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[GoogleTokenService] userId={} 토큰 갱신 중 오류 발생", userId, e);
            throw new CustomException(ErrorCode.TOKEN_REFRESH_FAILED);
        }
    }
}
