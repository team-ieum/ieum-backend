package com.ieum.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Slack Web API 호출 — 모든 메서드를 form POST로 부른다. Slack은 실패도 HTTP 200 + {@code ok:false}로 주므로 판정은
 * 호출자가 {@code ok}·{@code error}로 한다(HTTP 상태만 보면 실패가 조용히 성공이 된다). HTTP 4xx·5xx·네트워크 오류는
 * {@link RestClientException} 그대로 던진다. 응답 본문은 로그에 남기지 않는다 — 토큰이 들어 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackApiClient {

    static final String BASE_URL = "https://slack.com/api/";

    private final RestTemplate restTemplate;

    /** @param bearerToken 봇 토큰. 토큰 없이 부르는 oauth.v2.access는 null */
    public JsonNode call(String method, MultiValueMap<String, String> form, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        JsonNode body = restTemplate.postForObject(BASE_URL + method, new HttpEntity<>(form, headers), JsonNode.class);
        return body != null ? body : JsonNodeFactory.instance.objectNode().put("ok", false).put("error", "empty_response");
    }

    /** 봇 토큰 폐기(봇 비활성화 — 앱 제거 아님). best-effort라 던지지 않고, 토큰 값 없이 warn만 남긴다. */
    public void revokeQuietly(String token) {
        try {
            JsonNode body = call("auth.revoke", new LinkedMultiValueMap<>(), token);
            if (!body.path("ok").asBoolean(false)) {
                log.warn("[SlackApiClient] auth.revoke 거절 — error: {}", body.path("error").asText());
            }
        } catch (RestClientException e) {
            log.warn("[SlackApiClient] auth.revoke 호출 실패 — {}", e.getClass().getSimpleName());
        }
    }
}
