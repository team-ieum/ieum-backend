package com.ieum.ai.credential.service;

import com.ieum.ai.credential.domain.AiProvider;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ApiKeyFormatValidatorTest {

    // ===== CLAUDE =====

    @Test
    void claude_validKey_passes() {
        assertThatNoException().isThrownBy(() ->
                ApiKeyFormatValidator.validate(AiProvider.CLAUDE, "sk-ant-api03-testkey12345678"));
    }

    @Test
    void claude_wrongPrefix_throwsInvalidFormat() {
        assertThatThrownBy(() ->
                ApiKeyFormatValidator.validate(AiProvider.CLAUDE, "sk-openai-wrong-prefix12345"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_API_KEY_FORMAT));
    }

    // ===== OPENAI =====

    @Test
    void openai_validKey_passes() {
        assertThatNoException().isThrownBy(() ->
                ApiKeyFormatValidator.validate(AiProvider.OPENAI, "sk-proj-testkey1234567890"));
    }

    @Test
    void openai_wrongPrefix_throwsInvalidFormat() {
        assertThatThrownBy(() ->
                ApiKeyFormatValidator.validate(AiProvider.OPENAI, "AIzaSy-wrong-prefix123456"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_API_KEY_FORMAT));
    }

    // ===== GEMINI =====

    @Test
    void gemini_validKey_passes() {
        assertThatNoException().isThrownBy(() ->
                ApiKeyFormatValidator.validate(AiProvider.GEMINI, "AIzaSyTestKey12345678901"));
    }

    @Test
    void gemini_wrongPrefix_throwsInvalidFormat() {
        assertThatThrownBy(() ->
                ApiKeyFormatValidator.validate(AiProvider.GEMINI, "sk-ant-wrong-prefix123456"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_API_KEY_FORMAT));
    }

    // ===== 길이 검증 =====

    @Test
    void tooShortKey_throwsInvalidFormat() {
        assertThatThrownBy(() ->
                ApiKeyFormatValidator.validate(AiProvider.CLAUDE, "sk-ant-short"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_API_KEY_FORMAT));
    }
}
