package com.ieum.auth.service;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class GoogleTokenServiceTest {

    @Mock
    private ConnectedAccountRepository connectedAccountRepository;

    @Mock
    private AesEncryptor aesEncryptor;

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private GoogleTokenService googleTokenService;

    private final UUID userId = UUID.randomUUID();

    private static final String ENCRYPTED_ACCESS_TOKEN = "enc-access-token";
    private static final String ENCRYPTED_REFRESH_TOKEN = "enc-refresh-token";
    private static final String PLAIN_ACCESS_TOKEN = "ya29.plain-access-token";
    private static final String PLAIN_REFRESH_TOKEN = "1//plain-refresh-token";
    private static final String NEW_PLAIN_ACCESS_TOKEN = "ya29.new-access-token";
    private static final String ENCRYPTED_NEW_ACCESS_TOKEN = "enc-new-access-token";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(googleTokenService, "clientId", "test-client-id");
        ReflectionTestUtils.setField(googleTokenService, "clientSecret", "test-client-secret");
    }

    /** tokenExpiresAt 없는 기존 레코드 (tokeninfo API 폴백 경로) */
    private ConnectedAccount buildAccount(String encryptedRefreshToken) {
        return ConnectedAccount.builder()
            .userId(userId)
            .provider(AuthProvider.GOOGLE)
            .accessToken(ENCRYPTED_ACCESS_TOKEN)
            .refreshToken(encryptedRefreshToken)
            .build();
    }

    /** tokenExpiresAt이 설정된 레코드 (threshold 기반 경로) */
    private ConnectedAccount buildAccountWithExpiry(
            String encryptedRefreshToken,
            LocalDateTime tokenExpiresAt,
            LocalDateTime refreshTokenExpiresAt) {
        return ConnectedAccount.builder()
            .userId(userId)
            .provider(AuthProvider.GOOGLE)
            .accessToken(ENCRYPTED_ACCESS_TOKEN)
            .refreshToken(encryptedRefreshToken)
            .tokenExpiresAt(tokenExpiresAt)
            .refreshTokenExpiresAt(refreshTokenExpiresAt)
            .build();
    }

    // ── 1. 토큰 유효한 경우 ──────────────────────────────────────────────────

    @Test
    void getValidAccessToken_tokenValid_returnsDecryptedToken() {
        // given
        given(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE))
            .willReturn(Optional.of(buildAccount(ENCRYPTED_REFRESH_TOKEN)));
        given(aesEncryptor.decrypt(ENCRYPTED_ACCESS_TOKEN)).willReturn(PLAIN_ACCESS_TOKEN);

        // tokeninfo POST → 200 (유효)
        given(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
            .willReturn(new ResponseEntity<>(Map.of("aud", "test-client-id"), HttpStatus.OK));

        // when
        String result = googleTokenService.getValidAccessToken(userId);

        // then
        assertThat(result).isEqualTo(PLAIN_ACCESS_TOKEN);
        // 유효한 토큰이므로 refresh_token 복호화 없음
        then(aesEncryptor).should().decrypt(ENCRYPTED_ACCESS_TOKEN);
        then(aesEncryptor).shouldHaveNoMoreInteractions();
    }

    // ── 2. 토큰 만료 → 갱신 성공 ─────────────────────────────────────────────

    @Test
    void getValidAccessToken_tokenExpired_refreshSuccess_returnsNewToken() {
        // given
        given(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE))
            .willReturn(Optional.of(buildAccount(ENCRYPTED_REFRESH_TOKEN)));
        given(aesEncryptor.decrypt(ENCRYPTED_ACCESS_TOKEN)).willReturn(PLAIN_ACCESS_TOKEN);
        given(aesEncryptor.decrypt(ENCRYPTED_REFRESH_TOKEN)).willReturn(PLAIN_REFRESH_TOKEN);
        given(aesEncryptor.encrypt(NEW_PLAIN_ACCESS_TOKEN)).willReturn(ENCRYPTED_NEW_ACCESS_TOKEN);

        // 1차 postForEntity: tokeninfo → 401 (만료)
        // 2차 postForEntity: token endpoint → 200 (갱신 성공)
        given(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
            .willThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
            .willReturn(new ResponseEntity<>(
                Map.of("access_token", NEW_PLAIN_ACCESS_TOKEN, "expires_in", 3600),
                HttpStatus.OK));

        // when
        String result = googleTokenService.getValidAccessToken(userId);

        // then
        assertThat(result).isEqualTo(NEW_PLAIN_ACCESS_TOKEN);
        // 새 access_token이 암호화되어 DB에 저장되어야 한다
        then(aesEncryptor).should().encrypt(NEW_PLAIN_ACCESS_TOKEN);
    }

    // ── 3. 토큰 만료 + refresh_token 없음 → AUTHENTICATION_REQUIRED ─────────

    @Test
    void getValidAccessToken_tokenExpired_noRefreshToken_throwsAuthenticationRequired() {
        // given — refreshToken이 null인 계정
        given(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE))
            .willReturn(Optional.of(buildAccount(null)));
        given(aesEncryptor.decrypt(ENCRYPTED_ACCESS_TOKEN)).willReturn(PLAIN_ACCESS_TOKEN);

        // tokeninfo POST → 401 (만료)
        given(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
            .willThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        // when & then
        assertThatThrownBy(() -> googleTokenService.getValidAccessToken(userId))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    // ── 4. 토큰 만료 + Google이 refresh 거절 → AUTHENTICATION_REQUIRED ───────

    @Test
    void getValidAccessToken_tokenExpired_googleRejectsRefresh_throwsAuthenticationRequired() {
        // given
        given(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE))
            .willReturn(Optional.of(buildAccount(ENCRYPTED_REFRESH_TOKEN)));
        given(aesEncryptor.decrypt(ENCRYPTED_ACCESS_TOKEN)).willReturn(PLAIN_ACCESS_TOKEN);
        given(aesEncryptor.decrypt(ENCRYPTED_REFRESH_TOKEN)).willReturn(PLAIN_REFRESH_TOKEN);

        // 1차 postForEntity: tokeninfo → 401 (만료)
        // 2차 postForEntity: token endpoint → 400 invalid_grant
        given(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
            .willThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
            .willReturn(new ResponseEntity<>(
                Map.of("error", "invalid_grant"), HttpStatus.BAD_REQUEST));

        // when & then
        assertThatThrownBy(() -> googleTokenService.getValidAccessToken(userId))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    // ── 5. Google 연동 계정 없음 → ACCOUNT_NOT_CONNECTED ────────────────────

    @Test
    void getValidAccessToken_accountNotConnected_throwsAccountNotConnected() {
        // given
        given(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE))
            .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> googleTokenService.getValidAccessToken(userId))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_NOT_CONNECTED));
    }

    // ── 6. tokenExpiresAt 기반: 아직 유효 → decrypt만 하고 API 호출 없이 반환 ─

    @Test
    void getValidAccessToken_tokenExpiresAtFarFuture_returnsDirectlyWithoutApiCall() {
        // given — tokenExpiresAt이 1시간 후 (5분 threshold 이상 여유)
        ConnectedAccount account = buildAccountWithExpiry(
            ENCRYPTED_REFRESH_TOKEN,
            LocalDateTime.now().plusHours(1),
            LocalDateTime.now().plusDays(180)
        );
        given(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE))
            .willReturn(Optional.of(account));
        given(aesEncryptor.decrypt(ENCRYPTED_ACCESS_TOKEN)).willReturn(PLAIN_ACCESS_TOKEN);

        // when
        String result = googleTokenService.getValidAccessToken(userId);

        // then — tokeninfo API 호출 없이 바로 반환
        assertThat(result).isEqualTo(PLAIN_ACCESS_TOKEN);
        then(restTemplate).shouldHaveNoInteractions();
    }

    // ── 7. tokenExpiresAt 기반: 5분 이내 만료 예정 → 자동 갱신 ──────────────

    @Test
    void getValidAccessToken_tokenExpiringSoon_triggersRefreshWithoutApiCall() {
        // given — tokenExpiresAt이 3분 후 (5분 threshold 이내)
        ConnectedAccount account = buildAccountWithExpiry(
            ENCRYPTED_REFRESH_TOKEN,
            LocalDateTime.now().plusMinutes(3),
            LocalDateTime.now().plusDays(180)
        );
        given(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE))
            .willReturn(Optional.of(account));
        given(aesEncryptor.decrypt(ENCRYPTED_REFRESH_TOKEN)).willReturn(PLAIN_REFRESH_TOKEN);
        given(aesEncryptor.encrypt(NEW_PLAIN_ACCESS_TOKEN)).willReturn(ENCRYPTED_NEW_ACCESS_TOKEN);

        // token endpoint → 200 (갱신 성공)
        given(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
            .willReturn(new ResponseEntity<>(
                Map.of("access_token", NEW_PLAIN_ACCESS_TOKEN, "expires_in", 3600),
                HttpStatus.OK));

        // when
        String result = googleTokenService.getValidAccessToken(userId);

        // then — tokeninfo API 한 번도 호출 안 됨 (refresh만 1회)
        assertThat(result).isEqualTo(NEW_PLAIN_ACCESS_TOKEN);
        then(restTemplate).should().postForEntity(anyString(), any(), eq(Map.class));
    }

    // ── 8. Token Rotation: 응답에 새 refresh_token 포함 → DB 교체 ───────────

    @Test
    void getValidAccessToken_tokenRotation_encryptsAndSavesNewRefreshToken() {
        // given
        String newRefreshToken = "1//new-refresh-token";
        String encryptedNewRefreshToken = "enc-new-refresh-token";

        given(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE))
            .willReturn(Optional.of(buildAccount(ENCRYPTED_REFRESH_TOKEN)));
        given(aesEncryptor.decrypt(ENCRYPTED_ACCESS_TOKEN)).willReturn(PLAIN_ACCESS_TOKEN);
        given(aesEncryptor.decrypt(ENCRYPTED_REFRESH_TOKEN)).willReturn(PLAIN_REFRESH_TOKEN);
        given(aesEncryptor.encrypt(NEW_PLAIN_ACCESS_TOKEN)).willReturn(ENCRYPTED_NEW_ACCESS_TOKEN);
        given(aesEncryptor.encrypt(newRefreshToken)).willReturn(encryptedNewRefreshToken);

        // tokeninfo → 401, token endpoint → 200 with new refresh_token
        given(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
            .willThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
            .willReturn(new ResponseEntity<>(
                Map.of("access_token", NEW_PLAIN_ACCESS_TOKEN,
                    "refresh_token", newRefreshToken,
                    "expires_in", 3600),
                HttpStatus.OK));

        // when
        String result = googleTokenService.getValidAccessToken(userId);

        // then — 새 refresh_token도 암호화되어 저장됨
        assertThat(result).isEqualTo(NEW_PLAIN_ACCESS_TOKEN);
        then(aesEncryptor).should().encrypt(newRefreshToken);
    }

    // ── 9. Refresh Token 만료 → 즉시 AUTHENTICATION_REQUIRED ────────────────

    @Test
    void getValidAccessToken_refreshTokenExpired_throwsAuthenticationRequired() {
        // given — refreshTokenExpiresAt이 과거
        ConnectedAccount account = buildAccountWithExpiry(
            ENCRYPTED_REFRESH_TOKEN,
            LocalDateTime.now().plusHours(1),
            LocalDateTime.now().minusDays(1)   // refresh token 이미 만료
        );
        given(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.GOOGLE))
            .willReturn(Optional.of(account));

        // when & then — tokeninfo API 호출 없이 즉시 예외
        assertThatThrownBy(() -> googleTokenService.getValidAccessToken(userId))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
        then(restTemplate).shouldHaveNoInteractions();
    }
}
