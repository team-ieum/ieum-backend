package com.ieum.ai.credential.service;

import com.ieum.ai.credential.domain.AiProvider;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class CredentialValidatorTest {

    @Mock private RestTemplate restTemplate;

    @InjectMocks
    private CredentialValidator credentialValidator;

    // ===== CLAUDE =====

    @Test
    void validate_claude_200_returnsSuccess() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willReturn(ResponseEntity.ok("{}"));

        CredentialValidationResult result = credentialValidator.validate(AiProvider.CLAUDE, "valid-key");
        assertThat(result.valid()).isTrue();
        assertThat(result.failureReason()).isNull();
    }

    @Test
    void validate_claude_401_returnsFailed() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                        HttpHeaders.EMPTY, null, null));

        CredentialValidationResult result = credentialValidator.validate(AiProvider.CLAUDE, "invalid-key");
        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).isNotBlank();
    }

    @Test
    void validate_claude_403_returnsFailed() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                        HttpHeaders.EMPTY, null, null));

        CredentialValidationResult result = credentialValidator.validate(AiProvider.CLAUDE, "invalid-key");
        assertThat(result.valid()).isFalse();
    }

    @Test
    void validate_claude_429_returnsSuccess() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        HttpHeaders.EMPTY, null, null));

        CredentialValidationResult result = credentialValidator.validate(AiProvider.CLAUDE, "valid-but-rate-limited");
        assertThat(result.valid()).isTrue();
    }

    @Test
    void validate_claude_500_throwsProviderUnavailable() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                        HttpHeaders.EMPTY, null, null));

        assertThatThrownBy(() -> credentialValidator.validate(AiProvider.CLAUDE, "any-key"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE));
    }

    @Test
    void validate_claude_timeout_throwsValidationTimeout() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willThrow(new ResourceAccessException("timeout", new SocketTimeoutException()));

        assertThatThrownBy(() -> credentialValidator.validate(AiProvider.CLAUDE, "any-key"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CREDENTIAL_VALIDATION_TIMEOUT));
    }

    @Test
    void validate_claude_networkError_throwsNetworkError() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willThrow(new ResourceAccessException("connection refused"));

        assertThatThrownBy(() -> credentialValidator.validate(AiProvider.CLAUDE, "any-key"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CREDENTIAL_VALIDATION_NETWORK_ERROR));
    }

    // ===== OPENAI =====

    @Test
    void validate_openai_200_returnsSuccess() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willReturn(ResponseEntity.ok("{}"));

        CredentialValidationResult result = credentialValidator.validate(AiProvider.OPENAI, "sk-openai-key");
        assertThat(result.valid()).isTrue();
    }

    @Test
    void validate_openai_401_returnsFailed() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                        HttpHeaders.EMPTY, null, null));

        CredentialValidationResult result = credentialValidator.validate(AiProvider.OPENAI, "invalid-key");
        assertThat(result.valid()).isFalse();
    }

    // ===== GEMINI =====

    @Test
    void validate_gemini_200_returnsSuccess() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willReturn(ResponseEntity.ok("{}"));

        CredentialValidationResult result = credentialValidator.validate(AiProvider.GEMINI, "gemini-key");
        assertThat(result.valid()).isTrue();
    }

    @Test
    void validate_gemini_400_returnsFailed() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                        HttpHeaders.EMPTY, null, null));

        CredentialValidationResult result = credentialValidator.validate(AiProvider.GEMINI, "invalid-key");
        assertThat(result.valid()).isFalse();
    }

    @Test
    void validate_gemini_429_returnsSuccess() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        HttpHeaders.EMPTY, null, null));

        CredentialValidationResult result = credentialValidator.validate(AiProvider.GEMINI, "valid-but-rate-limited");
        assertThat(result.valid()).isTrue();
    }
}
