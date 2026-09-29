package com.ieum.api.credential.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.api.credential.domain.AiProvider;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * 프로바이더 키 검증의 판정 규칙을 고정한다 (IEUM-BE-66).
 *
 * <p>세 프로바이더 모두 모델 목록 GET으로 검증하므로, 요청 모양은 프로바이더별로 보고 응답 판정은
 * 프로바이더를 가리지 않고 같은지 본다.
 */
class CredentialValidatorTest {

    private static final String GEMINI_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final CredentialValidator validator = new CredentialValidator(restTemplate);

    @AfterEach
    void verifyRequestSent() {
        server.verify();
    }

    @Test
    @DisplayName("Gemini 200이면 유효 — 키는 쿼리스트링이 아니라 x-goog-api-key 헤더로 나간다")
    void geminiOkIsValidAndKeyGoesInHeader() {
        server.expect(requestTo(GEMINI_MODELS_URL))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("x-goog-api-key", "gemini-key"))
            .andRespond(withSuccess());

        CredentialValidationResult result = validator.validate(AiProvider.GEMINI, "gemini-key");

        assertThat(result.valid()).isTrue();
        assertThat(result.provider()).isEqualTo("GEMINI");
        assertThat(result.failureReason()).isNull();
    }

    @Test
    @DisplayName("Claude는 x-api-key·anthropic-version 헤더로 모델 목록을 조회한다 — 200이면 유효")
    void claudeOkIsValid() {
        server.expect(requestTo("https://api.anthropic.com/v1/models"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("x-api-key", "claude-key"))
            .andExpect(header("anthropic-version", "2023-06-01"))
            .andRespond(withSuccess());

        CredentialValidationResult result = validator.validate(AiProvider.CLAUDE, "claude-key");

        assertThat(result.valid()).isTrue();
        assertThat(result.provider()).isEqualTo("CLAUDE");
    }

    @Test
    @DisplayName("OpenAI는 Bearer 헤더로 모델 목록을 조회한다 — 200이면 유효")
    void openAiOkIsValid() {
        server.expect(requestTo("https://api.openai.com/v1/models"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer openai-key"))
            .andRespond(withSuccess());

        CredentialValidationResult result = validator.validate(AiProvider.OPENAI, "openai-key");

        assertThat(result.valid()).isTrue();
        assertThat(result.provider()).isEqualTo("OPENAI");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "CLAUDE, 401", "CLAUDE, 403",
        "OPENAI, 401", "OPENAI, 403",
        "GEMINI, 401", "GEMINI, 403",
    })
    @DisplayName("401/403이면 예외 없이 무효 판정 — provider는 enum 이름")
    void authErrorIsInvalid(AiProvider provider, int status) {
        server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(status)));

        CredentialValidationResult result = validator.validate(provider, "bad-key");

        assertThat(result.valid()).isFalse();
        assertThat(result.provider()).isEqualTo(provider.name());
        assertThat(result.failureReason()).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(AiProvider.class)
    @DisplayName("429는 판정하지 않고 PROVIDER_UNAVAILABLE — 본문에 quota·billing이 있어도 결제 오류로 보지 않는다")
    void rateLimitIsUndetermined(AiProvider provider) {
        server.expect(anything())
            .andRespond(withStatus(HttpStatusCode.valueOf(429))
                .body("{\"error\":{\"message\":\"You exceeded your current quota, please check your plan and billing details.\"}}"));

        assertErrorCode(provider, ErrorCode.PROVIDER_UNAVAILABLE);
    }

    @ParameterizedTest
    @EnumSource(AiProvider.class)
    @DisplayName("402면 CREDENTIAL_NO_BILLING")
    void paymentRequiredIsNoBilling(AiProvider provider) {
        server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(402)));

        assertErrorCode(provider, ErrorCode.CREDENTIAL_NO_BILLING);
    }

    @ParameterizedTest
    @EnumSource(AiProvider.class)
    @DisplayName("5xx면 PROVIDER_UNAVAILABLE")
    void serverErrorIsProviderUnavailable(AiProvider provider) {
        server.expect(anything()).andRespond(withServerError());

        assertErrorCode(provider, ErrorCode.PROVIDER_UNAVAILABLE);
    }

    @ParameterizedTest
    @EnumSource(AiProvider.class)
    @DisplayName("응답 시간 초과면 CREDENTIAL_VALIDATION_TIMEOUT")
    void timeoutIsValidationTimeout(AiProvider provider) {
        server.expect(anything()).andRespond(withException(new SocketTimeoutException()));

        assertErrorCode(provider, ErrorCode.CREDENTIAL_VALIDATION_TIMEOUT);
    }

    @ParameterizedTest
    @EnumSource(AiProvider.class)
    @DisplayName("연결 실패면 CREDENTIAL_VALIDATION_NETWORK_ERROR")
    void connectFailureIsNetworkError(AiProvider provider) {
        server.expect(anything()).andRespond(withException(new ConnectException()));

        assertErrorCode(provider, ErrorCode.CREDENTIAL_VALIDATION_NETWORK_ERROR);
    }

    private void assertErrorCode(AiProvider provider, ErrorCode expected) {
        assertThatThrownBy(() -> validator.validate(provider, "any-key"))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(expected);
    }
}
