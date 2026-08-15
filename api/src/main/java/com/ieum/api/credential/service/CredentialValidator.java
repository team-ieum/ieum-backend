package com.ieum.api.credential.service;

import com.ieum.api.credential.domain.AiProvider;
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
            return CredentialValidationResult.success(AiProvider.CLAUDE.name());
        } catch (HttpClientErrorException e) {
            return handleClientError(AiProvider.CLAUDE, e, true);
        } catch (ResourceAccessException e) {
            return handleResourceAccessError(AiProvider.CLAUDE, e);
        } catch (HttpServerErrorException e) {
            return handleServerError(AiProvider.CLAUDE, e);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] 검증 중 예상치 못한 예외 발생", AiProvider.CLAUDE, e);
            return CredentialValidationResult.failed(AiProvider.CLAUDE.name(), "검증 중 알 수 없는 오류가 발생했습니다.");
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
            return CredentialValidationResult.success(AiProvider.OPENAI.name());
        } catch (HttpClientErrorException e) {
            return handleClientError(AiProvider.OPENAI, e, true);
        } catch (ResourceAccessException e) {
            return handleResourceAccessError(AiProvider.OPENAI, e);
        } catch (HttpServerErrorException e) {
            return handleServerError(AiProvider.OPENAI, e);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] 검증 중 예상치 못한 예외 발생", AiProvider.OPENAI, e);
            return CredentialValidationResult.failed(AiProvider.OPENAI.name(), "검증 중 알 수 없는 오류가 발생했습니다.");
        }
    }

    /**
     * Claude·OpenAI와 달리 생성 요청이 아니라 모델 목록 조회로 검증한다. 특정 모델의
     * {@code generateContent}를 부르면 그 모델에 접근 권한이 없는 키(무료 등급·조직 정책)가 404·403을
     * 받아 <b>멀쩡한 키가 '유효하지 않음'으로 저장된다.</b> 목록 조회는 키 자체만 보므로 모델 개명·티어
     * 변경에 영향받지 않는다. 대신 생성 권한까지는 확인하지 못한다 — 그건 실행 시점에 드러난다.
     */
    private CredentialValidationResult validateGemini(String apiKey) {
        try {
            // 키는 쿼리스트링이 아니라 헤더로 보낸다 — URL에 실으면 RestTemplate DEBUG 로그와
            // 중간 프록시 access log에 원문이 그대로 남는다.
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-goog-api-key", apiKey);

            restTemplate.exchange(
                    "https://generativelanguage.googleapis.com/v1beta/models",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );
            return CredentialValidationResult.success(AiProvider.GEMINI.name());
        } catch (HttpClientErrorException e) {
            return handleClientError(AiProvider.GEMINI, e, false);
        } catch (ResourceAccessException e) {
            return handleResourceAccessError(AiProvider.GEMINI, e);
        } catch (HttpServerErrorException e) {
            return handleServerError(AiProvider.GEMINI, e);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] 검증 중 예상치 못한 예외 발생", AiProvider.GEMINI, e);
            return CredentialValidationResult.failed(AiProvider.GEMINI.name(), "검증 중 알 수 없는 오류가 발생했습니다.");
        }
    }

    /**
     * @param generationCall 검증이 <b>생성 요청</b>으로 이뤄졌는지. 결제·쿼터 판정은 생성 호출에서만
     *     의미가 있다 — 메타데이터 호출(Gemini 모델 목록)의 429는 분당 레이트리밋이지 결제 신호가
     *     아니라서, 여기에 걸리면 결제 수단이 멀쩡한 사용자에게 "결제 수단 없음"이 뜬다.
     */
    private CredentialValidationResult handleClientError(
            AiProvider provider, HttpClientErrorException e, boolean generationCall) {
        String name = provider.name();
        String displayName = provider.getDisplayName();
        int status = e.getStatusCode().value();
        if (generationCall && status == 429 && isBillingError(e)) {
            throw new CustomException(ErrorCode.CREDENTIAL_NO_BILLING,
                    displayName + " 계정에 결제 수단이 등록되어 있지 않거나 크레딧이 부족합니다.");
        }
        if (status == 402) {
            throw new CustomException(ErrorCode.CREDENTIAL_NO_BILLING,
                    displayName + " 계정에 결제 수단이 등록되어 있지 않습니다.");
        }
        if (status == 429) {
            return CredentialValidationResult.success(name);
        }
        if (status == 401 || status == 403) {
            return CredentialValidationResult.failed(name, "API 키가 유효하지 않습니다. 키를 확인해주세요.");
        }
        if (status == 400) {
            return CredentialValidationResult.failed(name, "API 키가 유효하지 않습니다.");
        }
        if (status >= 500) {
            throw new CustomException(ErrorCode.PROVIDER_UNAVAILABLE,
                    displayName + " 서버에 일시적 장애가 발생했습니다 (HTTP " + status + "). 잠시 후 다시 시도해주세요.");
        }
        log.warn("[{}] 검증 실패 - status: {}", name, e.getStatusCode());
        return CredentialValidationResult.failed(name, "검증 중 예상치 못한 오류가 발생했습니다 (HTTP " + status + ").");
    }

    private CredentialValidationResult handleResourceAccessError(AiProvider provider, ResourceAccessException e) {
        if (e.getCause() instanceof SocketTimeoutException) {
            throw new CustomException(ErrorCode.CREDENTIAL_VALIDATION_TIMEOUT,
                    provider.getDisplayName() + " 서버 응답 시간이 초과되었습니다.");
        }
        throw new CustomException(ErrorCode.CREDENTIAL_VALIDATION_NETWORK_ERROR,
                provider.getDisplayName() + " 서버에 연결할 수 없습니다.");
    }

    private CredentialValidationResult handleServerError(AiProvider provider, HttpServerErrorException e) {
        throw new CustomException(ErrorCode.PROVIDER_UNAVAILABLE,
                provider.getDisplayName() + " 서버에 일시적 장애가 발생했습니다 (HTTP "
                        + e.getStatusCode().value() + ").");
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
