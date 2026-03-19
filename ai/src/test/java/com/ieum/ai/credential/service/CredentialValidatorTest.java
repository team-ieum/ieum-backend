package com.ieum.ai.credential.service;

import com.ieum.ai.credential.domain.AiProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

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
    void validate_claude_200_returnsTrue() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willReturn(ResponseEntity.ok("{}"));

        assertThat(credentialValidator.validate(AiProvider.CLAUDE, "valid-key")).isTrue();
    }

    @Test
    void validate_claude_401_returnsFalse() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                        HttpHeaders.EMPTY, null, null));

        assertThat(credentialValidator.validate(AiProvider.CLAUDE, "invalid-key")).isFalse();
    }

    @Test
    void validate_claude_403_returnsFalse() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                        HttpHeaders.EMPTY, null, null));

        assertThat(credentialValidator.validate(AiProvider.CLAUDE, "invalid-key")).isFalse();
    }

    @Test
    void validate_claude_exception_returnsFalse() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willThrow(new RuntimeException("connection timeout"));

        assertThat(credentialValidator.validate(AiProvider.CLAUDE, "any-key")).isFalse();
    }

    // ===== OPENAI =====

    @Test
    void validate_openai_200_returnsTrue() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willReturn(ResponseEntity.ok("{}"));

        assertThat(credentialValidator.validate(AiProvider.OPENAI, "sk-openai-key")).isTrue();
    }

    @Test
    void validate_openai_401_returnsFalse() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                        HttpHeaders.EMPTY, null, null));

        assertThat(credentialValidator.validate(AiProvider.OPENAI, "invalid-key")).isFalse();
    }

    // ===== GEMINI =====

    @Test
    void validate_gemini_200_returnsTrue() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willReturn(ResponseEntity.ok("{}"));

        assertThat(credentialValidator.validate(AiProvider.GEMINI, "gemini-key")).isTrue();
    }

    @Test
    void validate_gemini_400_returnsFalse() {
        given(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .willThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                        HttpHeaders.EMPTY, null, null));

        assertThat(credentialValidator.validate(AiProvider.GEMINI, "invalid-key")).isFalse();
    }
}
