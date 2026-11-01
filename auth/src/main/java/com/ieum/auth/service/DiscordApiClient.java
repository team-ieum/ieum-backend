package com.ieum.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * Discord HTTP API v10 호출 전부(spec §5). Slack과 달리 실패는 HTTP 상태로 온다 — 4xx·5xx·네트워크 오류는
 * {@link RestClientException} 그대로 던지고, 판정은 호출자가 상태와 {@link #errorCode} 본문 code로 한다.
 * 429만 여기서 1회 재시도(retry_after ≤ 5초). 401·403은 재시도하지 않는다 — 공유 봇의 invalid request 한도(spec §13).
 * 봇 토큰·응답 본문은 로그·예외 메시지에 넣지 않는다.
 */
@Slf4j
@Component
public class DiscordApiClient {

    static final String BASE_URL = "https://discord.com/api/v10";
    /** Discord 문서상 필수 — 없으면 Cloudflare가 막을 수 있다. agent 도구도 같은 값(계약) */
    public static final String USER_AGENT = "DiscordBot (https://green.kiacms.co.kr, 1.0)";
    static final double MAX_RETRY_AFTER_SECONDS = 5.0;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestTemplate restTemplate;
    private final String clientId;
    private final String clientSecret;
    private final String botToken;

    public DiscordApiClient(RestTemplate restTemplate,
                            @Value("${discord.oauth.client-id}") String clientId,
                            @Value("${discord.oauth.client-secret}") String clientSecret,
                            @Value("${discord.bot-token}") String botToken) {
        this.restTemplate = restTemplate;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.botToken = botToken;
    }

    /** 설치 콜백의 code 교환. 응답에 access_token과 설치된 guild가 온다(bot_require_code_grant) */
    public JsonNode exchangeCode(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        return send(HttpMethod.POST, "/oauth2/token", basicForm(), form);
    }

    /** 사용자 access token 폐기 — 우리는 쓰지 않는다. best-effort라 던지지 않고 토큰 값 없이 warn만 남긴다 */
    public void revokeQuietly(String accessToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", accessToken);
        form.add("token_type_hint", "access_token");
        try {
            send(HttpMethod.POST, "/oauth2/token/revoke", basicForm(), form);
        } catch (RestClientException e) {
            log.warn("[DiscordApiClient] 사용자 토큰 revoke 실패 — {}", describe(e));
        }
    }

    public JsonNode currentUser(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return send(HttpMethod.GET, "/users/@me", headers, null);
    }

    public JsonNode guild(String guildId) {
        return send(HttpMethod.GET, "/guilds/{id}", bot(), null, guildId);
    }

    public JsonNode guildChannels(String guildId) {
        return send(HttpMethod.GET, "/guilds/{id}/channels", bot(), null, guildId);
    }

    /** 봇이 서버를 나간다(204) */
    public void leaveGuild(String guildId) {
        send(HttpMethod.DELETE, "/users/@me/guilds/{id}", bot(), null, guildId);
    }

    /** 오류 본문의 Discord code(10004·50001 등). 없거나 JSON이 아니면 -1 */
    public static int errorCode(RestClientResponseException e) {
        try {
            return JSON.readTree(e.getResponseBodyAsString()).path("code").asInt(-1);
        } catch (Exception parseFailure) {
            return -1;
        }
    }

    /** 로그용 — 상태와 Discord code만(본문 금지) */
    public static String describe(RestClientException e) {
        if (e instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value() + " code=" + errorCode(response);
        }
        return e.getClass().getSimpleName();
    }

    private HttpHeaders bot() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bot " + botToken);
        return headers;
    }

    private HttpHeaders basicForm() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(clientId, clientSecret);
        return headers;
    }

    private JsonNode send(HttpMethod method, String path, HttpHeaders headers, Object body, Object... uriVars) {
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        try {
            return exchange(method, path, entity, uriVars);
        } catch (HttpClientErrorException.TooManyRequests e) {
            double retryAfter = retryAfterSeconds(e);
            if (retryAfter < 0 || retryAfter > MAX_RETRY_AFTER_SECONDS) {
                throw e;
            }
            try {
                Thread.sleep((long) Math.ceil(retryAfter * 1000));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw e;
            }
            return exchange(method, path, entity, uriVars);
        }
    }

    private JsonNode exchange(HttpMethod method, String path, HttpEntity<Object> entity, Object... uriVars) {
        JsonNode body = restTemplate.exchange(BASE_URL + path, method, entity, JsonNode.class, uriVars).getBody();
        return body != null ? body : JsonNodeFactory.instance.objectNode(); // 204(퇴장)는 본문이 없다
    }

    private static double retryAfterSeconds(RestClientResponseException e) {
        try {
            return JSON.readTree(e.getResponseBodyAsString()).path("retry_after").asDouble(-1);
        } catch (Exception parseFailure) {
            return -1;
        }
    }
}
