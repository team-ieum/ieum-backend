package com.ieum.ai.credential.service;

import com.ieum.ai.credential.domain.AiProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialValidator {

    private final RestTemplate restTemplate;

    public boolean validate(AiProvider provider, String decryptedApiKey) {
        return switch (provider) {
            case CLAUDE -> validateClaude(decryptedApiKey);
            case OPENAI -> validateOpenAI(decryptedApiKey);
            case GEMINI -> validateGemini(decryptedApiKey);
        };
    }

    private boolean validateClaude(String apiKey) {
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
            return true;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                return false;
            }
            log.warn("[CredentialValidator] Claude 검증 실패 - status: {}", e.getStatusCode());
            return false;
        } catch (Exception e) {
            log.warn("[CredentialValidator] Claude 검증 중 예외 발생 - {}", e.getMessage());
            return false;
        }
    }

    private boolean validateOpenAI(String apiKey) {
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
            return true;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                return false;
            }
            log.warn("[CredentialValidator] OpenAI 검증 실패 - status: {}", e.getStatusCode());
            return false;
        } catch (Exception e) {
            log.warn("[CredentialValidator] OpenAI 검증 중 예외 발생 - {}", e.getMessage());
            return false;
        }
    }

    private boolean validateGemini(String apiKey) {
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
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + apiKey,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );
            return true;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                return false;
            }
            log.warn("[CredentialValidator] Gemini 검증 실패 - status: {}", e.getStatusCode());
            return false;
        } catch (Exception e) {
            log.warn("[CredentialValidator] Gemini 검증 중 예외 발생 - {}", e.getMessage());
            return false;
        }
    }
}
