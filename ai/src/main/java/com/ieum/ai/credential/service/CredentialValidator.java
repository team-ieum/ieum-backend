package com.ieum.ai.credential.service;

import com.ieum.ai.credential.domain.AiProvider;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialValidator {

    private final RestTemplate restTemplate;

    public CredentialValidationResult validate(AiProvider provider, String decryptedApiKey) {
        return switch (provider) {
            case CLAUDE -> validateClaude(decryptedApiKey);
            case OPENAI -> validateOpenAI(decryptedApiKey);
            case GEMINI -> validateGemini(decryptedApiKey);
        };
    }

    private CredentialValidationResult validateClaude(String apiKey) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            String body = """
                    {
                      "model": "claude-haiku-4-5-20251001",
                      "max_tokens": 10,
                      "messages": [{"role": "user", "content": "ping"}]
                    }
                    """;

            restTemplate.exchange(
                    "https://api.anthropic.com/v1/messages",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );
            return CredentialValidationResult.success("CLAUDE");
        } catch (HttpClientErrorException e) {
            return handleClientError("Claude", e);
        } catch (ResourceAccessException e) {
            return handleResourceAccessError("Claude", e);
        } catch (HttpServerErrorException e) {
            return handleServerError("Claude", e);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Claude] 검증 중 예상치 못한 예외 발생", e);
            return CredentialValidationResult.failed("CLAUDE", "검증 중 알 수 없는 오류가 발생했습니다.");
        }
    }

    private CredentialValidationResult validateOpenAI(String apiKey) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            String body = """
                    {
                      "model": "gpt-4o-mini",
                      "max_tokens": 10,
                      "messages": [{"role": "user", "content": "ping"}]
                    }
                    """;

            restTemplate.exchange(
                    "https://api.openai.com/v1/chat/completions",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );
            return CredentialValidationResult.success("OPENAI");
        } catch (HttpClientErrorException e) {
            return handleClientError("OpenAI", e);
        } catch (ResourceAccessException e) {
            return handleResourceAccessError("OpenAI", e);
        } catch (HttpServerErrorException e) {
            return handleServerError("OpenAI", e);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[OpenAI] 검증 중 예상치 못한 예외 발생", e);
            return CredentialValidationResult.failed("OPENAI", "검증 중 알 수 없는 오류가 발생했습니다.");
        }
    }

    private CredentialValidationResult validateGemini(String apiKey) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String body = """
                    {
                      "contents": [{"parts": [{"text": "ping"}]}],
                      "generationConfig": {"maxOutputTokens": 10}
                    }
                    """;

            restTemplate.exchange(
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );
            return CredentialValidationResult.success("GEMINI");
        } catch (HttpClientErrorException e) {
            return handleClientError("Gemini", e);
        } catch (ResourceAccessException e) {
            return handleResourceAccessError("Gemini", e);
        } catch (HttpServerErrorException e) {
            return handleServerError("Gemini", e);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Gemini] 검증 중 예상치 못한 예외 발생", e);
            return CredentialValidationResult.failed("GEMINI", "검증 중 알 수 없는 오류가 발생했습니다.");
        }
    }

    private CredentialValidationResult handleClientError(String providerName, HttpClientErrorException e) {
        int status = e.getStatusCode().value();
        if (status == 429 && isBillingError(e)) {
            throw new CustomException(ErrorCode.CREDENTIAL_NO_BILLING,
                    providerName + " 계정에 결제 수단이 등록되어 있지 않거나 크레딧이 부족합니다.");
        }
        if (status == 402) {
            throw new CustomException(ErrorCode.CREDENTIAL_NO_BILLING,
                    providerName + " 계정에 결제 수단이 등록되어 있지 않습니다.");
        }
        if (status == 429) {
            return CredentialValidationResult.success(providerName);
        }
        if (status == 401 || status == 403) {
            return CredentialValidationResult.failed(providerName, "API 키가 유효하지 않습니다. 키를 확인해주세요.");
        }
        if (status == 400) {
            return CredentialValidationResult.failed(providerName, "API 키가 유효하지 않습니다.");
        }
        if (status >= 500) {
            throw new CustomException(ErrorCode.PROVIDER_UNAVAILABLE,
                    providerName + " 서버에 일시적 장애가 발생했습니다 (HTTP " + status + "). 잠시 후 다시 시도해주세요.");
        }
        log.warn("[{}] 검증 실패 - status: {}", providerName, e.getStatusCode());
        return CredentialValidationResult.failed(providerName, "검증 중 예상치 못한 오류가 발생했습니다 (HTTP " + status + ").");
    }

    private CredentialValidationResult handleResourceAccessError(String providerName, ResourceAccessException e) {
        if (e.getCause() instanceof SocketTimeoutException) {
            throw new CustomException(ErrorCode.CREDENTIAL_VALIDATION_TIMEOUT,
                    providerName + " 서버 응답 시간이 초과되었습니다.");
        }
        throw new CustomException(ErrorCode.CREDENTIAL_VALIDATION_NETWORK_ERROR,
                providerName + " 서버에 연결할 수 없습니다.");
    }

    private CredentialValidationResult handleServerError(String providerName, HttpServerErrorException e) {
        throw new CustomException(ErrorCode.PROVIDER_UNAVAILABLE,
                providerName + " 서버에 일시적 장애가 발생했습니다 (HTTP " + e.getStatusCode().value() + ").");
    }

    /**
     * 429 응답 본문에 결제/할당량 관련 키워드가 포함되어 있는지 확인.
     * OpenAI는 결제 미설정 시에도 429를 반환하면서 "quota" 또는 "billing" 메시지를 포함한다.
     */
    private boolean isBillingError(HttpClientErrorException e) {
        String responseBody = e.getResponseBodyAsString().toLowerCase();
        return responseBody.contains("billing")
                || responseBody.contains("quota")
                || responseBody.contains("payment")
                || responseBody.contains("insufficient");
    }
}
