package com.ieum.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/** Discord 호출 모양(spec §5) — 헤더 3종, 429 1회 재시도, 401·403 무재시도, 로그 문자열에 본문 없음. */
class DiscordApiClientTest {

    private static final String BASE = "https://discord.com/api/v10";
    private static final String G1 = "111111111111111111";
    private static final String UA = "DiscordBot (https://green.kiacms.co.kr, 1.0)";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final DiscordApiClient client = new DiscordApiClient(restTemplate, "cid", "secret", "bot-token");

    @AfterEach
    void verifyRequests() {
        // 기대 없는 요청은 그 시점에 AssertionError(No further requests expected) — 재시도가 새면 테스트가 깨진다
        server.verify();
    }

    @Test
    @DisplayName("상수 User-Agent는 계약 값 그대로(agent 도구와 같다)")
    void userAgentConstant() {
        assertThat(DiscordApiClient.USER_AGENT).isEqualTo(UA);
    }

    @Test
    @DisplayName("봇 호출 — Authorization: Bot <토큰> + User-Agent, 응답 본문 그대로")
    void botCallCarriesBotTokenAndUserAgent() {
        server.expect(requestTo(BASE + "/guilds/" + G1))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bot bot-token"))
            .andExpect(header(HttpHeaders.USER_AGENT, UA))
            .andRespond(withSuccess("{\"id\":\"" + G1 + "\",\"name\":\"이음\"}", MediaType.APPLICATION_JSON));

        assertThat(client.guild(G1).path("name").asText()).isEqualTo("이음");
    }

    @Test
    @DisplayName("채널 목록·봇 퇴장 경로 — 퇴장은 204(본문 없음)여도 예외 없음")
    void channelsAndLeavePaths() {
        server.expect(requestTo(BASE + "/guilds/" + G1 + "/channels"))
            .andExpect(header(HttpHeaders.USER_AGENT, UA))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/users/@me/guilds/" + G1))
            .andExpect(method(HttpMethod.DELETE))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bot bot-token"))
            .andExpect(header(HttpHeaders.USER_AGENT, UA))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThat(client.guildChannels(G1).isArray()).isTrue();
        assertThatCode(() -> client.leaveGuild(G1)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("code 교환 — form(grant_type·code·redirect_uri) + Basic client_id:client_secret + User-Agent")
    void exchangeUsesBasicForm() {
        server.expect(requestTo(BASE + "/oauth2/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic Y2lkOnNlY3JldA=="))
            .andExpect(header(HttpHeaders.USER_AGENT, UA))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().formDataContains(Map.of(
                "grant_type", "authorization_code", "code", "c1", "redirect_uri", "https://x/cb")))
            .andRespond(withSuccess("{\"access_token\":\"at\",\"guild\":{\"id\":\"" + G1 + "\"}}", MediaType.APPLICATION_JSON));

        assertThat(client.exchangeCode("c1", "https://x/cb").path("guild").path("id").asText()).isEqualTo(G1);
    }

    @Test
    @DisplayName("/users/@me는 Bearer 사용자 토큰")
    void currentUserUsesBearer() {
        server.expect(requestTo(BASE + "/users/@me"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer at"))
            .andExpect(header(HttpHeaders.USER_AGENT, UA))
            .andRespond(withSuccess("{\"id\":\"999\"}", MediaType.APPLICATION_JSON));

        assertThat(client.currentUser("at").path("id").asText()).isEqualTo("999");
    }

    @Test
    @DisplayName("revoke — form token·token_type_hint + Basic, 5xx여도 던지지 않는다")
    void revokeIsQuiet() {
        server.expect(requestTo(BASE + "/oauth2/token/revoke"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic Y2lkOnNlY3JldA=="))
            .andExpect(content().formDataContains(Map.of("token", "at", "token_type_hint", "access_token")))
            .andRespond(withServerError());

        assertThatCode(() -> client.revokeQuietly("at")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("429 + retry_after ≤ 5초 → 1회 기다려 재시도해 성공")
    void rateLimitedRetriesOnce() {
        server.expect(requestTo(BASE + "/guilds/" + G1))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"You are being rate limited.\",\"retry_after\":0.01,\"global\":false}"));
        server.expect(requestTo(BASE + "/guilds/" + G1))
            .andRespond(withSuccess("{\"name\":\"이음\"}", MediaType.APPLICATION_JSON));

        assertThat(client.guild(G1).path("name").asText()).isEqualTo("이음");
    }

    @Test
    @DisplayName("429 + retry_after > 5초 → 재시도 없이 실패")
    void rateLimitedOverFiveSecondsFails() {
        server.expect(once(), requestTo(BASE + "/guilds/" + G1))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                .body("{\"retry_after\":6.5}"));

        assertThatThrownBy(() -> client.guild(G1)).isInstanceOf(HttpClientErrorException.TooManyRequests.class);
    }

    @Test
    @DisplayName("재시도도 429면 실패 — 요청은 정확히 두 번")
    void secondRateLimitFails() {
        server.expect(twice(), requestTo(BASE + "/guilds/" + G1))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                .body("{\"retry_after\":0.01}"));

        assertThatThrownBy(() -> client.guild(G1)).isInstanceOf(HttpClientErrorException.TooManyRequests.class);
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"UNAUTHORIZED", "FORBIDDEN"})
    @DisplayName("401·403은 재시도하지 않는다 — 공유 봇의 invalid request 한도(spec §13)")
    void authErrorsAreNotRetried(HttpStatus status) {
        server.expect(once(), requestTo(BASE + "/guilds/" + G1))
            .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"Missing Access\",\"code\":50001}"));

        assertThatThrownBy(() -> client.guild(G1)).isInstanceOf(HttpClientErrorException.class);
    }

    @Test
    @DisplayName("errorCode는 본문 code, describe는 상태·code만 — 본문(토큰 등)은 로그 문자열에 없다")
    void errorCodeAndDescribe() {
        HttpClientErrorException e = HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY,
            "{\"message\":\"Unknown Guild\",\"code\":10004,\"secret\":\"bot-token\"}".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);
        HttpClientErrorException notJson = HttpClientErrorException.create(HttpStatus.BAD_GATEWAY, "x", HttpHeaders.EMPTY,
            "<html>".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThat(DiscordApiClient.errorCode(e)).isEqualTo(10004);
        assertThat(DiscordApiClient.errorCode(notJson)).isEqualTo(-1);
        assertThat(DiscordApiClient.describe(e)).isEqualTo("HTTP 404 code=10004").doesNotContain("bot-token");
    }
}
