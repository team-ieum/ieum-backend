package com.ieum.api.oauth.service;

import static com.ieum.api.oauth.DiscordOAuthConfigTest.D6;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.auth.domain.DiscordGuildLink;
import com.ieum.auth.domain.DiscordOAuthState;
import com.ieum.auth.domain.User;
import com.ieum.auth.repository.DiscordGuildLinkRepository;
import com.ieum.auth.repository.DiscordOAuthStateRepository;
import com.ieum.auth.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class DiscordOAuthServiceIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DiscordOAuthService discordOAuthService;
    @MockitoBean private DiscordOAuthStateRepository stateRepository;
    @MockitoBean private UserRepository userRepository;
    @Autowired private DiscordGuildLinkRepository linkRepository;
    @Autowired private RestTemplate restTemplate;
    private MockRestServiceServer server;

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    @BeforeEach
    void bindServer() {
        server = MockRestServiceServer.bindTo(restTemplate).build();
        given(userRepository.findByIdForUpdate(any())).willReturn(Optional.of(mock(User.class)));
    }

    @AfterEach
    void verifyAndClean() {
        try {
            server.verify();
        } finally {
            linkRepository.deleteAll();
        }
    }

    @Test
    @DisplayName("authorize — state(userId·nonce의 SHA-256·TTL 300)를 저장하고 bot+identify·D6 권한·code grant로 설치 URL을 만든다")
    void authorizeSavesStateAndBuildsUrl() {
        UUID userId = UUID.randomUUID();

        DiscordOAuthService.Authorization result = discordOAuthService.authorize(userId);

        ArgumentCaptor<DiscordOAuthState> saved = ArgumentCaptor.forClass(DiscordOAuthState.class);
        verify(stateRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getTtl()).isEqualTo(300L);
        assertThat(saved.getValue().getNonceHash())
            .isEqualTo(DiscordOAuthService.sha256Hex(result.nonce()))
            .isNotEqualTo(result.nonce());
        assertThat(Base64.getUrlDecoder().decode(result.nonce())).hasSize(32);

        assertThat(result.authUrl())
            .startsWith("https://discord.com/oauth2/authorize?")
            .contains("scope=bot%20identify")
            .contains("permissions=" + D6);
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(result.authUrl()).build().getQueryParams();
        assertThat(query.getFirst("client_id")).isEqualTo("test-discord-client-id");
        assertThat(decode(query.getFirst("scope"))).isEqualTo("bot identify");
        assertThat(query.getFirst("permissions")).isEqualTo("275146460176");
        assertThat(query.getFirst("response_type")).isEqualTo("code");
        assertThat(decode(query.getFirst("redirect_uri"))).isEqualTo("http://localhost/api/v1/discord/oauth2/callback");
        assertThat(query.getFirst("state")).isEqualTo(saved.getValue().getState());
        assertThat(query.getFirst("integration_type")).isEqualTo("0");
    }

    @Test
    @DisplayName("authorize·해제는 로그인 필요 — 토큰 없이 401")
    void endpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/discord/oauth2/authorize")).andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/discord/oauth2/connections/111111111111111111"))
            .andExpect(status().isUnauthorized());
    }

    private static final String CALLBACK = "/api/v1/discord/oauth2/callback";
    private static final String FRONT = "http://localhost/settings/integrations?";
    private static final String NONCE = "nonce-1";
    private static final String API = "https://discord.com/api/v10";
    private static final String G1 = "111111111111111111";
    private static final String BASIC = "Basic dGVzdC1kaXNjb3JkLWNsaWVudC1pZDp0ZXN0LWRpc2NvcmQtY2xpZW50LXNlY3JldA==";
    private static final String UA = "DiscordBot (https://green.kiacms.co.kr, 1.0)";

    /** 교환 응답 — bot scope + code grant면 설치된 guild가 온다 */
    private static String installed(String guildId, String guildName) {
        return """
            {"access_token":"user-at","token_type":"Bearer","expires_in":604800,"refresh_token":"user-rt",
             "scope":"bot identify","guild":{"id":"%s","name":"%s"}}""".formatted(guildId, guildName);
    }

    private void givenState(String state, UUID userId) {
        given(stateRepository.findById(state)).willReturn(Optional.of(DiscordOAuthState.builder()
            .state(state).userId(userId).nonceHash(DiscordOAuthService.sha256Hex(NONCE)).ttl(300L).build()));
    }

    private ResultActions callback(String state, String cookieNonce) throws Exception {
        MockHttpServletRequestBuilder request = get(CALLBACK).param("state", state).param("code", "c1");
        if (cookieNonce != null) {
            request.cookie(new Cookie("__Host-discord_oauth_nonce", cookieNonce));
        }
        return mockMvc.perform(request);
    }

    // MockMvcResultMatchers.header와 이름이 겹쳐 요청 헤더 매처는 정규화 이름으로 쓴다
    private static org.springframework.test.web.client.RequestMatcher requestHeader(String name, String value) {
        return org.springframework.test.web.client.match.MockRestRequestMatchers.header(name, value);
    }

    private void expectExchange(String body) {
        server.expect(requestTo(API + "/oauth2/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(requestHeader("Authorization", BASIC))
            .andExpect(requestHeader("User-Agent", UA))
            .andExpect(content().formDataContains(Map.of(
                "grant_type", "authorization_code", "code", "c1",
                "redirect_uri", "http://localhost/api/v1/discord/oauth2/callback")))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectMe(String discordUserId) {
        server.expect(requestTo(API + "/users/@me"))
            .andExpect(requestHeader("Authorization", "Bearer user-at"))
            .andRespond(withSuccess("{\"id\":\"" + discordUserId + "\"}", MediaType.APPLICATION_JSON));
    }

    private void expectRevoke() {
        server.expect(requestTo(API + "/oauth2/token/revoke"))
            .andExpect(requestHeader("Authorization", BASIC))
            .andExpect(content().formDataContains(Map.of("token", "user-at")))
            .andRespond(withStatus(HttpStatus.OK));
    }

    private void expectLeave(String guildId) {
        server.expect(requestTo(API + "/users/@me/guilds/" + guildId))
            .andExpect(method(HttpMethod.DELETE))
            .andExpect(requestHeader("Authorization", "Bot test-discord-bot-token"))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));
    }

    @Test
    @DisplayName("callback은 로그인 없이 열린다(permitAll) — 모르는 state면 discord_state_expired로 302")
    void callbackIsPublicAndUnknownStateExpires() throws Exception {
        given(stateRepository.findById("nope")).willReturn(Optional.empty());

        callback("nope", NONCE)
            .andExpect(status().isFound())
            .andExpect(header().string("Location", FRONT + "error=discord_state_expired"));
    }

    @Test
    @DisplayName("[Review Focus 5] state 파라미터가 아예 없어도 400이 아니라 discord_state_expired로 302")
    void callbackWithoutStateRedirectsExpired() throws Exception {
        mockMvc.perform(get(CALLBACK).param("error", "access_denied"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", FRONT + "error=discord_state_expired"));
    }

    @Test
    @DisplayName("2-2 nonce 쿠키가 없으면(다른 브라우저) 교환하지 않고 discord_state_mismatch — state는 소비된다")
    void callbackWithoutCookieDoesNotExchange() throws Exception {
        givenState("s1", UUID.randomUUID());

        callback("s1", null)
            .andExpect(header().string("Location", FRONT + "error=discord_state_mismatch"));

        verify(stateRepository).delete(any(DiscordOAuthState.class));
        assertThat(linkRepository.count()).isZero();
    }

    @Test
    @DisplayName("2-2 nonce 쿠키가 다르면 교환하지 않고 discord_state_mismatch")
    void callbackWithWrongCookieDoesNotExchange() throws Exception {
        givenState("s1", UUID.randomUUID());

        callback("s1", "someone-elses-nonce")
            .andExpect(header().string("Location", FRONT + "error=discord_state_mismatch"));
    }

    @Test
    @DisplayName("2-3 사용자가 취소(error 파라미터)하면 교환 없이 discord_denied")
    void callbackDenied() throws Exception {
        givenState("s1", UUID.randomUUID());

        mockMvc.perform(get(CALLBACK).param("state", "s1").param("error", "access_denied")
                .cookie(new Cookie("__Host-discord_oauth_nonce", NONCE)))
            .andExpect(header().string("Location", FRONT + "error=discord_denied"));
    }

    @Test
    @DisplayName("2-4 교환 거절(400 invalid_grant)·5xx → discord_exchange, 저장 없음")
    void callbackExchangeFailure() throws Exception {
        givenState("s1", UUID.randomUUID());
        givenState("s2", UUID.randomUUID());
        server.expect(requestTo(API + "/oauth2/token"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_grant\"}"));
        server.expect(requestTo(API + "/oauth2/token")).andRespond(withServerError());

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "error=discord_exchange"));
        callback("s2", NONCE).andExpect(header().string("Location", FRONT + "error=discord_exchange"));

        assertThat(linkRepository.count()).isZero();
    }

    @Test
    @DisplayName("2-5 교환 응답에 guild가 없으면 받은 사용자 토큰을 revoke하고 discord_exchange")
    void callbackWithoutGuildRevokes() throws Exception {
        givenState("s1", UUID.randomUUID());
        expectExchange("{\"access_token\":\"user-at\",\"scope\":\"identify\"}");
        expectRevoke();

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "error=discord_exchange"));

        assertThat(linkRepository.count()).isZero();
    }

    @Test
    @DisplayName("2-6~2-8 첫 연결 → /users/@me·사용자 토큰 revoke 뒤 링크 저장, connected=discord. 쿼리 guild_id는 무시하고 교환 응답 guild를 쓴다")
    void callbackConnects() throws Exception {
        UUID userA = UUID.randomUUID();
        givenState("s1", userA);
        expectExchange(installed(G1, "이음 서버"));
        expectMe("555555555555555555");
        expectRevoke();

        mockMvc.perform(get(CALLBACK).param("state", "s1").param("code", "c1")
                .param("guild_id", "999999999999999999").param("permissions", "275146460176")
                .cookie(new Cookie("__Host-discord_oauth_nonce", NONCE)))
            .andExpect(header().string("Location", FRONT + "connected=discord"));

        assertThat(linkRepository.findAllByUserIdOrderByCreatedAtAsc(userA)).singleElement().satisfies(link -> {
            assertThat(link.getGuildId()).isEqualTo(G1);
            assertThat(link.getGuildName()).isEqualTo("이음 서버");
            assertThat(link.getDiscordUserId()).isEqualTo("555555555555555555");
        });
    }

    @Test
    @DisplayName("2-6 /users/@me가 실패해도 연결한다(discord_user_id NULL)")
    void callbackMeFailureStillConnects() throws Exception {
        UUID userA = UUID.randomUUID();
        givenState("s1", userA);
        expectExchange(installed(G1, "이음 서버"));
        server.expect(requestTo(API + "/users/@me")).andRespond(withServerError());
        expectRevoke();

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "connected=discord"));

        assertThat(linkRepository.findByUserIdAndGuildId(userA, G1)).get()
            .extracting(DiscordGuildLink::getDiscordUserId).isNull();
    }

    @Test
    @DisplayName("2-6·2-7 교환 응답에 access_token이 없으면 /users/@me·revoke 없이 연결한다(discord_user_id NULL)")
    void callbackWithoutAccessTokenSkipsMeAndRevoke() throws Exception {
        UUID userA = UUID.randomUUID();
        givenState("s1", userA);
        expectExchange("{\"guild\":{\"id\":\"" + G1 + "\",\"name\":\"이음 서버\"}}");

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "connected=discord"));

        assertThat(linkRepository.findAllByUserIdOrderByCreatedAtAsc(userA)).singleElement()
            .extracting(DiscordGuildLink::getDiscordUserId).isNull();
    }

    @Test
    @DisplayName("같은 서버를 두 사용자가 연결 → 링크 2개(D1·D2)")
    void sameGuildTwoUsers() throws Exception {
        givenState("s1", UUID.randomUUID());
        givenState("s2", UUID.randomUUID());
        expectExchange(installed(G1, "이음 서버"));
        expectMe("1");
        expectRevoke();
        expectExchange(installed(G1, "이음 서버"));
        expectMe("2");
        expectRevoke();

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "connected=discord"));
        callback("s2", NONCE).andExpect(header().string("Location", FRONT + "connected=discord"));

        assertThat(linkRepository.countByGuildId(G1)).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 사용자가 같은 서버를 다시 연결 → 링크 1개 그대로, 서버 이름·Discord 사용자 갱신")
    void sameUserReconnectRefreshes() throws Exception {
        UUID userA = UUID.randomUUID();
        linkRepository.save(new DiscordGuildLink(userA, G1, "옛 이름", "1"));
        givenState("s1", userA);
        expectExchange(installed(G1, "새 이름"));
        expectMe("2");
        expectRevoke();

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "connected=discord"));

        assertThat(linkRepository.findAllByUserIdOrderByCreatedAtAsc(userA)).singleElement().satisfies(link -> {
            assertThat(link.getGuildName()).isEqualTo("새 이름");
            assertThat(link.getDiscordUserId()).isEqualTo("2");
        });
    }

    @Test
    @DisplayName("다시 연결 때 /users/@me가 실패하면 저장된 Discord 사용자를 지우지 않는다(서버 이름은 갱신)")
    void reconnectMeFailureKeepsInstaller() throws Exception {
        UUID userA = UUID.randomUUID();
        linkRepository.save(new DiscordGuildLink(userA, G1, "옛 이름", "1"));
        givenState("s1", userA);
        expectExchange(installed(G1, "새 이름"));
        server.expect(requestTo(API + "/users/@me")).andRespond(withServerError());
        expectRevoke();

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "connected=discord"));

        assertThat(linkRepository.findAllByUserIdOrderByCreatedAtAsc(userA)).singleElement().satisfies(link -> {
            assertThat(link.getGuildName()).isEqualTo("새 이름");
            assertThat(link.getDiscordUserId()).isEqualTo("1");
        });
    }

    @Test
    @DisplayName("2-9 state를 만든 사용자가 그새 탈퇴 + 그 서버 다른 링크 없음 → 링크 없이 봇 퇴장, discord_state_expired")
    void callbackAfterWithdrawalLeavesOrphanGuild() throws Exception {
        UUID gone = UUID.randomUUID();
        givenState("s1", gone);
        given(userRepository.findByIdForUpdate(gone)).willReturn(Optional.empty());
        expectExchange(installed(G1, "이음 서버"));
        expectMe("1");
        expectRevoke();
        expectLeave(G1);

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "error=discord_state_expired"));

        assertThat(linkRepository.count()).isZero();
    }

    @Test
    @DisplayName("2-9 탈퇴한 사용자 + 그 서버에 다른 사용자 링크 있음 → 봇은 남는다")
    void callbackAfterWithdrawalKeepsBotWhenOthersLinked() throws Exception {
        UUID gone = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        linkRepository.save(new DiscordGuildLink(other, G1, "이음 서버", "9"));
        givenState("s1", gone);
        given(userRepository.findByIdForUpdate(gone)).willReturn(Optional.empty());
        expectExchange(installed(G1, "이음 서버"));
        expectMe("1");
        expectRevoke();

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "error=discord_state_expired"));

        assertThat(linkRepository.findAll()).extracting(DiscordGuildLink::getUserId).containsExactly(other);
    }

    @Test
    @DisplayName("2-9 저장 실패(discord_user_id 32자 초과) → 500 JSON이 아니라 discord_exchange로 302, 봇 퇴장 판정")
    void callbackSaveFailureLeavesOrphanGuild() throws Exception {
        givenState("s1", UUID.randomUUID());
        expectExchange(installed(G1, "이음 서버"));
        expectMe("9".repeat(40));
        expectRevoke();
        expectLeave(G1);

        callback("s1", NONCE)
            .andExpect(status().isFound())
            .andExpect(header().string("Location", FRONT + "error=discord_exchange"));

        assertThat(linkRepository.count()).isZero();
    }

    // spec §4-2-4 — 교환 응답만 끊긴(처리됐을 수 있는) 실패에서만 쿼리 guild_id 힌트로 퇴장 판정
    private ResultActions callbackWithHint(String state, String guildIdHint) throws Exception {
        MockHttpServletRequestBuilder request = get(CALLBACK).param("state", state).param("code", "c1")
            .cookie(new Cookie("__Host-discord_oauth_nonce", NONCE));
        if (guildIdHint != null) {
            request.param("guild_id", guildIdHint);
        }
        return mockMvc.perform(request);
    }

    private void expectExchangeTimeout() {
        server.expect(requestTo(API + "/oauth2/token")).andRespond(request -> {
            throw new java.net.SocketTimeoutException("Read timed out");
        });
    }

    @Test
    @DisplayName("2-4 교환 read timeout + 유효 힌트 + 그 서버 링크 0 → 힌트 서버에서 봇 퇴장, discord_exchange")
    void exchangeTimeoutLeavesHintGuild() throws Exception {
        givenState("s1", UUID.randomUUID());
        expectExchangeTimeout();
        expectLeave(G1);

        callbackWithHint("s1", G1).andExpect(header().string("Location", FRONT + "error=discord_exchange"));
    }

    @Test
    @DisplayName("2-4 교환 5xx + 유효 힌트 + 링크 0 → 힌트 서버에서 봇 퇴장")
    void exchange5xxLeavesHintGuild() throws Exception {
        givenState("s1", UUID.randomUUID());
        server.expect(requestTo(API + "/oauth2/token")).andRespond(withServerError());
        expectLeave(G1);

        callbackWithHint("s1", G1).andExpect(header().string("Location", FRONT + "error=discord_exchange"));
    }

    @Test
    @DisplayName("2-4 교환 timeout + 유효 힌트지만 그 서버에 다른 사용자 링크 있음 → 퇴장 요청 없음")
    void exchangeTimeoutKeepsBotWhenOthersLinked() throws Exception {
        linkRepository.save(new DiscordGuildLink(UUID.randomUUID(), G1, "이음 서버", "9"));
        givenState("s1", UUID.randomUUID());
        expectExchangeTimeout();

        callbackWithHint("s1", G1).andExpect(header().string("Location", FRONT + "error=discord_exchange"));
    }

    @Test
    @DisplayName("2-4 교환 400(invalid_grant = 처리 안 됨) + 유효 힌트 → 퇴장 요청 없음")
    void exchange4xxIgnoresHint() throws Exception {
        givenState("s1", UUID.randomUUID());
        server.expect(requestTo(API + "/oauth2/token"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_grant\"}"));

        callbackWithHint("s1", G1).andExpect(header().string("Location", FRONT + "error=discord_exchange"));
    }

    @Test
    @DisplayName("2-4 교환 5xx + 힌트 형식 불일치('1/../2')·없음 → 퇴장 요청 없음")
    void exchange5xxIgnoresMalformedOrMissingHint() throws Exception {
        givenState("s1", UUID.randomUUID());
        givenState("s2", UUID.randomUUID());
        server.expect(requestTo(API + "/oauth2/token")).andRespond(withServerError());
        server.expect(requestTo(API + "/oauth2/token")).andRespond(withServerError());

        callbackWithHint("s1", "1/../2").andExpect(header().string("Location", FRONT + "error=discord_exchange"));
        callbackWithHint("s2", null).andExpect(header().string("Location", FRONT + "error=discord_exchange"));
    }
}
