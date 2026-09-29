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

/**
 * 프로바이더 API 키를 모델 목록 조회로 검증한다. 특정 모델로 생성 요청을 보내면 그 모델에 접근 권한이 없는
 * 키(무료 등급·조직 정책)나 모델 폐기 때문에 <b>멀쩡한 키가 '유효하지 않음'으로 저장된다.</b> 목록 조회는
 * 키 자체만 보므로 모델 개명·티어 변경에 영향받지 않는다.
 * 대신 생성 권한과 결제 상태는 보증하지 않는다 — 결제 수단이 없는 키도 여기선 유효로 통과하고 실행 시 실패한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialValidator {

    private final RestTemplate restTemplate;

    public CredentialValidationResult validate(AiProvider provider, String decryptedApiKey) {
        HttpHeaders headers = new HttpHeaders();
        String url = switch (provider) {
            case CLAUDE -> {
                headers.set("x-api-key", decryptedApiKey);
                headers.set("anthropic-version", "2023-06-01");
                yield "https://api.anthropic.com/v1/models";
            }
            case OPENAI -> {
                headers.setBearerAuth(decryptedApiKey);
                yield "https://api.openai.com/v1/models";
            }
            case GEMINI -> {
                // 키는 쿼리스트링이 아니라 헤더로 보낸다 — URL에 실으면 RestTemplate DEBUG 로그와
                // 중간 프록시 access log에 원문이 그대로 남는다.
                headers.set("x-goog-api-key", decryptedApiKey);
                yield "https://generativelanguage.googleapis.com/v1beta/models";
            }
        };
        return check(provider, url, headers);
    }

    private CredentialValidationResult check(AiProvider provider, String url, HttpHeaders headers) {
        try {
            restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return CredentialValidationResult.success(provider.name());
        } catch (HttpClientErrorException e) {
            return handleClientError(provider, e);
        } catch (ResourceAccessException e) {
            return handleResourceAccessError(provider, e);
        } catch (HttpServerErrorException e) {
            return handleServerError(provider, e);
        } catch (Exception e) {
            log.error("[{}] 검증 중 예상치 못한 예외 발생", provider, e);
            return CredentialValidationResult.failed(provider.name(), "검증 중 알 수 없는 오류가 발생했습니다.");
        }
    }

    private CredentialValidationResult handleClientError(AiProvider provider, HttpClientErrorException e) {
        String name = provider.name();
        String displayName = provider.getDisplayName();
        int status = e.getStatusCode().value();
        // 목록 조회가 결제를 보증하진 않지만, 프로바이더가 402를 명시적으로 주면 그 신호는 살린다.
        if (status == 402) {
            throw new CustomException(ErrorCode.CREDENTIAL_NO_BILLING,
                    displayName + " 계정에 결제 수단이 등록되어 있지 않습니다.");
        }
        if (status == 429) {
            // 목록 조회의 429는 레이트리밋이라 키 유효성에 대해 아무것도 말해 주지 않는다 — 여기서 success를
            // 반환하면 쿼터가 마른 키가 isValid=true로 영속화되어 '유효' 배지를 단 채 실행마다 실패한다.
            // 판정하지 않고 예외로 끊어 아무것도 저장되지 않게 한다.
            throw new CustomException(ErrorCode.PROVIDER_UNAVAILABLE,
                    displayName + " 요청이 일시적으로 제한되어 키를 확인하지 못했습니다. 잠시 후 다시 시도해주세요.");
        }
        if (status == 401 || status == 403) {
            return CredentialValidationResult.failed(name, "API 키가 유효하지 않습니다. 키를 확인해주세요.");
        }
        if (status == 400) {
            return CredentialValidationResult.failed(name, "API 키가 유효하지 않습니다.");
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
}
