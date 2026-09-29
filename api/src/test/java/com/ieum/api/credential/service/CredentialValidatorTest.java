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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * 프로바이더 키 검증의 판정 규칙을 고정한다 (IEUM-BE-66).
 *
 * <p>Claude·OpenAI는 검증 요청 자체(메서드·URL·모델)가 바뀔 예정이라 요청 모양은 단언하지 않고
 * 판정 결과만 본다.
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

    @Test
    @DisplayName("Gemini 429는 판정하지 않고 PROVIDER_UNAVAILABLE — 본문에 quota가 있어도 결제 오류로 보지 않는다")
    void geminiRateLimitIsUndetermined() {
        server.expect(requestTo(GEMINI_MODELS_URL))
            .andRespond(withStatus(HttpStatusCode.valueOf(429))
                .body("{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"Quota exceeded\"}}"));

        assertErrorCode(AiProvider.GEMINI, ErrorCode.PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("5xx면 PROVIDER_UNAVAILABLE")
    void serverErrorIsProviderUnavailable() {
        server.expect(requestTo(GEMINI_MODELS_URL)).andRespond(withServerError());

        assertErrorCode(AiProvider.GEMINI, ErrorCode.PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("응답 시간 초과면 CREDENTIAL_VALIDATION_TIMEOUT")
    void timeoutIsValidationTimeout() {
        server.expect(requestTo(GEMINI_MODELS_URL)).andRespond(withException(new SocketTimeoutException()));

        assertErrorCode(AiProvider.GEMINI, ErrorCode.CREDENTIAL_VALIDATION_TIMEOUT);
    }

    @Test
    @DisplayName("연결 실패면 CREDENTIAL_VALIDATION_NETWORK_ERROR")
    void connectFailureIsNetworkError() {
        server.expect(requestTo(GEMINI_MODELS_URL)).andRespond(withException(new ConnectException()));

        assertErrorCode(AiProvider.GEMINI, ErrorCode.CREDENTIAL_VALIDATION_NETWORK_ERROR);
    }

    private void assertErrorCode(AiProvider provider, ErrorCode expected) {
        assertThatThrownBy(() -> validator.validate(provider, "any-key"))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(expected);
    }
}
