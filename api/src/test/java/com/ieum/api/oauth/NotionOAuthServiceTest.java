package com.ieum.api.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.ConnectedAccount;
import com.ieum.auth.repository.ConnectedAccountRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.common.util.AesEncryptionService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class NotionOAuthServiceTest {

    @Mock
    private ConnectedAccountRepository connectedAccountRepository;

    @Mock
    private AesEncryptionService aesEncryptionService;

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private NotionOAuthService notionOAuthService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(notionOAuthService, "notionTokenUrl", "https://api.notion.com/v1/oauth/token");
        ReflectionTestUtils.setField(notionOAuthService, "clientId", "test-client");
        ReflectionTestUtils.setField(notionOAuthService, "clientSecret", "test-secret");
        ReflectionTestUtils.setField(notionOAuthService, "redirectUri", "http://localhost/callback");
    }

    @Test
    @DisplayName("토큰 교환 성공 시 신규 ConnectedAccount를 저장한다")
    void handleCallback_Success_NewAccount() {
        // given
        String code = "test-code";
        UUID userId = UUID.randomUUID();
        String accessToken = "notion-access-token";
        String encryptedToken = "encrypted-token";

        ResponseEntity<Map<String, Object>> responseEntity = new ResponseEntity<>(Map.of("access_token", accessToken), HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
            .thenReturn(responseEntity);
        when(aesEncryptionService.encrypt(accessToken)).thenReturn(encryptedToken);
        when(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.NOTION))
            .thenReturn(Optional.empty());

        // when
        notionOAuthService.handleCallback(code, userId);

        // then
        verify(connectedAccountRepository).save(any(ConnectedAccount.class));
    }

    @Test
    @DisplayName("토큰 교환 성공 시 기존 ConnectedAccount가 있으면 업데이트한다")
    void handleCallback_Success_UpdateAccount() {
        // given
        String code = "test-code";
        UUID userId = UUID.randomUUID();
        String accessToken = "notion-access-token";
        String encryptedToken = "encrypted-token";

        ConnectedAccount existingAccount = ConnectedAccount.builder()
            .userId(userId)
            .provider(AuthProvider.NOTION)
            .accessToken("old-token")
            .build();

        ResponseEntity<Map<String, Object>> responseEntity = new ResponseEntity<>(Map.of("access_token", accessToken), HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
            .thenReturn(responseEntity);
        when(aesEncryptionService.encrypt(accessToken)).thenReturn(encryptedToken);
        when(connectedAccountRepository.findByUserIdAndProvider(userId, AuthProvider.NOTION))
            .thenReturn(Optional.of(existingAccount));

        // when
        notionOAuthService.handleCallback(code, userId);

        // then
        assertThat(existingAccount.getAccessToken()).isEqualTo(encryptedToken);
    }

    @Test
    @DisplayName("Notion API가 4xx 응답을 반환하면 TOKEN_REFRESH_FAILED 예외가 발생한다")
    void handleCallback_Fail_NotionApi4xx() {
        // given
        String code = "invalid-code";
        UUID userId = UUID.randomUUID();

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
            .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        // when & then
        assertThatThrownBy(() -> notionOAuthService.handleCallback(code, userId))
            .isInstanceOf(CustomException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_REFRESH_FAILED);
    }

    @Test
    @DisplayName("Notion API 응답에 access_token이 없으면 TOKEN_REFRESH_FAILED 예외가 발생한다")
    void handleCallback_Fail_NoAccessToken() {
        // given
        String code = "valid-code";
        UUID userId = UUID.randomUUID();

        Map<String, Object> responseBody = new HashMap<>();
        ResponseEntity<Map<String, Object>> mockResponse = ResponseEntity.ok(responseBody);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
            .thenReturn(mockResponse);

        // when & then
        assertThatThrownBy(() -> notionOAuthService.handleCallback(code, userId))
            .isInstanceOf(CustomException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_REFRESH_FAILED);
    }

    @Test
    @DisplayName("RestClientException 발생 시 TOKEN_REFRESH_FAILED 예외로 래핑된다")
    void handleCallback_Fail_NetworkError() {
        // given
        String code = "valid-code";
        UUID userId = UUID.randomUUID();

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
            .thenThrow(new ResourceAccessException("Connection refused"));

        // when & then
        assertThatThrownBy(() -> notionOAuthService.handleCallback(code, userId))
            .isInstanceOf(CustomException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_REFRESH_FAILED);
    }
}
