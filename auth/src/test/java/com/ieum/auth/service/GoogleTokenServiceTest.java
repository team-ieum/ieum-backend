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

    private ConnectedAccount buildAccount(String encryptedRefreshToken) {
        return ConnectedAccount.builder()
            .userId(userId)
            .provider(AuthProvider.GOOGLE)
            .accessToken(ENCRYPTED_ACCESS_TOKEN)
            .refreshToken(encryptedRefreshToken)
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
}
