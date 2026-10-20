package com.ieum.api.oauth.service;

import static com.ieum.api.oauth.SlackOAuthConfigTest.D3;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.auth.domain.SlackInstallation;
import com.ieum.auth.domain.SlackOAuthState;
import com.ieum.auth.domain.SlackUserLink;
import com.ieum.auth.repository.SlackInstallationRepository;
import com.ieum.auth.repository.SlackOAuthStateRepository;
import com.ieum.auth.repository.SlackUserLinkRepository;
import com.ieum.common.util.AesEncryptor;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
class SlackOAuthServiceIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SlackOAuthService slackOAuthService;
    @MockitoBean private SlackOAuthStateRepository stateRepository;

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static final String CALLBACK = "/api/v1/slack/oauth2/callback";
    private static final String FRONT = "http://localhost/settings/integrations?";
    private static final String NONCE = "nonce-1";
    private static final String ACCESS_URL = "https://slack.com/api/oauth.v2.access";
    private static final String REVOKE_URL = "https://slack.com/api/auth.revoke";

    @Autowired private SlackInstallationRepository installationRepository;
    @Autowired private SlackUserLinkRepository linkRepository;
    @Autowired private AesEncryptor aesEncryptor;
    @Autowired private RestTemplate restTemplate;
    private MockRestServiceServer server;

    @BeforeEach
    void bindServer() {
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @AfterEach
    void verifyAndClean() {
        server.verify(); // 기대를 등록하지 않은 케이스는 Slack 호출이 하나도 없어야 통과한다
        linkRepository.deleteAll();
        installationRepository.deleteAll();
    }

    /** team·토큰·rotation 필드를 바꿔 끼우는 설치 응답 */
    private static String installed(String teamId, String extra) {
        return """
            {"ok":true,"access_token":"xoxb-new","token_type":"bot","scope":"chat:write,channels:read",
             "bot_user_id":"UBOT","app_id":"A1","team":{"id":"%s","name":"이음 팀"},"enterprise":null,
             "authed_user":{"id":"U1"},"is_enterprise_install":false%s}""".formatted(teamId, extra);
    }

    private void givenState(String state, UUID userId) {
        given(stateRepository.findById(state)).willReturn(Optional.of(SlackOAuthState.builder()
            .state(state).userId(userId).nonceHash(SlackOAuthService.sha256Hex(NONCE)).ttl(300L).build()));
    }

    private ResultActions callback(String state, String cookieNonce) throws Exception {
        MockHttpServletRequestBuilder request = get(CALLBACK).param("state", state).param("code", "c1");
        if (cookieNonce != null) {
            request.cookie(new Cookie("slack_oauth_nonce", cookieNonce));
        }
        return mockMvc.perform(request);
    }

    private void expectExchange(String body) {
        server.expect(requestTo(ACCESS_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().formDataContains(Map.of(
                "code", "c1", "client_id", "test-slack-client-id", "client_secret", "test-slack-client-secret",
                "redirect_uri", "http://localhost/api/v1/slack/oauth2/callback")))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectRevoke(String token) {
        // MockMvcResultMatchers.header와 이름이 겹쳐 정규화 이름으로 쓴다
        server.expect(requestTo(REVOKE_URL))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                "Authorization", "Bearer " + token))
            .andRespond(withSuccess("{\"ok\":true,\"revoked\":true}", MediaType.APPLICATION_JSON));
    }

    /** 이미 연결된 상태를 만든다 — 팀 teamId 설치 + userId 링크 */
    private SlackInstallation linkedTo(String teamId, UUID userId) {
        SlackInstallation installation = new SlackInstallation(teamId);
        installation.applyInstall(aesEncryptor.encrypt("xoxb-old"), null, null, "chat:write", "옛 팀", "UBOT", "A1");
        installationRepository.save(installation);
        linkRepository.save(new SlackUserLink(userId, installation, "U0"));
        return installation;
    }

    @Test
    @DisplayName("authorize — state(userId·nonce의 SHA-256·TTL 300)를 저장하고 D3 봇 scope로 설치 URL을 만든다(user_scope 없음)")
    void authorizeSavesStateAndBuildsUrl() {
        UUID userId = UUID.randomUUID();

        SlackOAuthService.Authorization result = slackOAuthService.authorize(userId);

        ArgumentCaptor<SlackOAuthState> saved = ArgumentCaptor.forClass(SlackOAuthState.class);
        verify(stateRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getTtl()).isEqualTo(300L);
        assertThat(saved.getValue().getNonceHash())
            .isEqualTo(SlackOAuthService.sha256Hex(result.nonce()))
            .isNotEqualTo(result.nonce());
        assertThat(Base64.getUrlDecoder().decode(result.nonce())).hasSize(32);

        assertThat(result.authUrl()).startsWith("https://slack.com/oauth/v2/authorize?");
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(result.authUrl()).build().getQueryParams();
        assertThat(query.getFirst("client_id")).isEqualTo("test-slack-client-id");
        assertThat(decode(query.getFirst("scope"))).isEqualTo(D3);
        assertThat(query.getFirst("state")).isEqualTo(saved.getValue().getState());
        assertThat(decode(query.getFirst("redirect_uri"))).isEqualTo("http://localhost/api/v1/slack/oauth2/callback");
        assertThat(query).doesNotContainKey("user_scope");
    }

    @Test
    @DisplayName("authorize는 로그인 필요 — 토큰 없이 401")
    void authorizeRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/slack/oauth2/authorize")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("callback은 로그인 없이 열린다(permitAll) — 모르는 state면 slack_state_expired로 302")
    void callbackIsPublicAndUnknownStateExpires() throws Exception {
        given(stateRepository.findById("nope")).willReturn(Optional.empty());

        callback("nope", NONCE)
            .andExpect(status().isFound())
            .andExpect(header().string("Location", FRONT + "error=slack_state_expired"));
    }

    @Test
    @DisplayName("[Review Focus 1] state 파라미터가 아예 없어도 400이 아니라 slack_state_expired로 302")
    void callbackWithoutStateRedirectsExpired() throws Exception {
        mockMvc.perform(get(CALLBACK).param("error", "access_denied"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", FRONT + "error=slack_state_expired"));
    }

    @Test
    @DisplayName("2-2 nonce 쿠키가 없으면(다른 브라우저) 교환하지 않고 slack_state_mismatch — state는 소비된다")
    void callbackWithoutCookieDoesNotExchange() throws Exception {
        givenState("s1", UUID.randomUUID());

        callback("s1", null)
            .andExpect(header().string("Location", FRONT + "error=slack_state_mismatch"));

        verify(stateRepository).delete(any(SlackOAuthState.class));
        assertThat(installationRepository.count()).isZero();
    }

    @Test
    @DisplayName("2-2 nonce 쿠키가 다르면 교환하지 않고 slack_state_mismatch")
    void callbackWithWrongCookieDoesNotExchange() throws Exception {
        givenState("s1", UUID.randomUUID());

        callback("s1", "someone-elses-nonce")
            .andExpect(header().string("Location", FRONT + "error=slack_state_mismatch"));
    }

    @Test
    @DisplayName("2-3 사용자가 취소(error 파라미터)하면 교환 없이 slack_denied")
    void callbackDenied() throws Exception {
        givenState("s1", UUID.randomUUID());

        mockMvc.perform(get(CALLBACK).param("state", "s1").param("error", "access_denied")
                .cookie(new Cookie("slack_oauth_nonce", NONCE)))
            .andExpect(header().string("Location", FRONT + "error=slack_denied"));
    }

    @Test
    @DisplayName("2-4 교환이 ok:false면 slack_exchange, 아무것도 저장하지 않는다")
    void callbackExchangeRejected() throws Exception {
        givenState("s1", UUID.randomUUID());
        expectExchange("{\"ok\":false,\"error\":\"invalid_code\"}");

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "error=slack_exchange"));

        assertThat(installationRepository.count()).isZero();
    }

    @Test
    @DisplayName("2-4 교환 HTTP 5xx도 slack_exchange")
    void callbackExchangeHttpFailure() throws Exception {
        givenState("s1", UUID.randomUUID());
        server.expect(requestTo(ACCESS_URL)).andRespond(withServerError());

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "error=slack_exchange"));
    }

    @Test
    @DisplayName("2-5 Enterprise 조직 설치 → 받은 토큰 revoke, slack_org_install, 저장 없음")
    void callbackEnterpriseInstallRevoked() throws Exception {
        givenState("s1", UUID.randomUUID());
        expectExchange("""
            {"ok":true,"access_token":"xoxb-new","token_type":"bot","team":null,
             "enterprise":{"id":"E1"},"is_enterprise_install":true}""");
        expectRevoke("xoxb-new");

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "error=slack_org_install"));

        assertThat(installationRepository.count()).isZero();
    }

    @Test
    @DisplayName("2-5 봇이 아닌 토큰(token_type=user) → revoke, slack_org_install")
    void callbackUserTokenRevoked() throws Exception {
        givenState("s1", UUID.randomUUID());
        expectExchange(installed("T1", "").replace("\"token_type\":\"bot\"", "\"token_type\":\"user\""));
        expectRevoke("xoxb-new");

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "error=slack_org_install"));

        assertThat(installationRepository.count()).isZero();
    }

    @Test
    @DisplayName("2-6 다른 팀에 이미 연결 + 그 팀 설치 있음 → 링크는 그대로, 그 팀 토큰은 갱신, revoke 없음, slack_other_team")
    void callbackOtherTeamWithExistingInstallation() throws Exception {
        UUID userA = UUID.randomUUID();
        SlackInstallation t1 = linkedTo("T1", userA);
        SlackInstallation t2 = linkedTo("T2", UUID.randomUUID());
        givenState("s1", userA);
        expectExchange(installed("T2", ""));

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "error=slack_other_team"));

        assertThat(linkRepository.findByUserId(userA)).get()
            .extracting(link -> link.getInstallation().getId()).isEqualTo(t1.getId());
        assertThat(aesEncryptor.decrypt(installationRepository.findById(t2.getId()).orElseThrow().getBotToken()))
            .isEqualTo("xoxb-new");
    }

    @Test
    @DisplayName("2-6 다른 팀에 이미 연결 + 그 팀 설치 없음 → 설치를 만들지 않고 받은 토큰 revoke, slack_other_team")
    void callbackOtherTeamWithoutInstallationRevokes() throws Exception {
        UUID userA = UUID.randomUUID();
        linkedTo("T1", userA);
        givenState("s1", userA);
        expectExchange(installed("T2", ""));
        expectRevoke("xoxb-new");

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "error=slack_other_team"));

        assertThat(installationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("2-7·2-8 첫 연결 → 설치·링크 저장(토큰 암호문, rotation 꺼짐이면 refresh·만료 null), connected=slack")
    void callbackConnects() throws Exception {
        UUID userA = UUID.randomUUID();
        givenState("s1", userA);
        expectExchange(installed("T1", ""));

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "connected=slack"));

        SlackInstallation installation = installationRepository.findAll().get(0);
        assertThat(installation.getTeamId()).isEqualTo("T1");
        assertThat(installation.getTeamName()).isEqualTo("이음 팀");
        assertThat(installation.getBotUserId()).isEqualTo("UBOT");
        assertThat(installation.getAppId()).isEqualTo("A1");
        assertThat(installation.getScopes()).isEqualTo("chat:write,channels:read");
        assertThat(installation.getBotToken()).isNotEqualTo("xoxb-new");
        assertThat(aesEncryptor.decrypt(installation.getBotToken())).isEqualTo("xoxb-new");
        assertThat(installation.getRefreshToken()).isNull();
        assertThat(installation.getTokenExpiresAt()).isNull();
        SlackUserLink link = linkRepository.findByUserId(userA).orElseThrow();
        assertThat(link.getInstallation().getId()).isEqualTo(installation.getId());
        assertThat(link.getSlackUserId()).isEqualTo("U1");
    }

    @Test
    @DisplayName("2-7 같은 사용자가 같은 팀에 다시 연결(rotation 켜진 응답) → 설치 1행을 덮어쓰고 refresh·만료를 채운다")
    void callbackReinstallOverwrites() throws Exception {
        UUID userA = UUID.randomUUID();
        linkedTo("T1", userA);
        givenState("s1", userA);
        expectExchange(installed("T1", ",\"refresh_token\":\"xoxe-1-r\",\"expires_in\":43200"));

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "connected=slack"));

        assertThat(installationRepository.count()).isEqualTo(1);
        assertThat(linkRepository.count()).isEqualTo(1);
        SlackInstallation installation = installationRepository.findAll().get(0);
        assertThat(aesEncryptor.decrypt(installation.getRefreshToken())).isEqualTo("xoxe-1-r");
        assertThat(installation.getTokenExpiresAt()).isAfter(LocalDateTime.now().plusHours(11));
        assertThat(linkRepository.findByUserId(userA).orElseThrow().getSlackUserId()).isEqualTo("U1");
    }

    @Test
    @DisplayName("같은 팀을 두 사용자가 연결 → 설치 1행, 링크 2개(D2)")
    void sameTeamTwoUsersShareInstallation() throws Exception {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        givenState("s1", userA);
        givenState("s2", userB);
        expectExchange(installed("T1", ""));
        expectExchange(installed("T1", ""));

        callback("s1", NONCE).andExpect(header().string("Location", FRONT + "connected=slack"));
        callback("s2", NONCE).andExpect(header().string("Location", FRONT + "connected=slack"));

        assertThat(installationRepository.count()).isEqualTo(1);
        assertThat(linkRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("2-7 저장이 재시도까지 실패(bot_user_id 32자 초과) → 500 JSON이 아니라 slack_exchange로 302, revoke 안 함")
    void callbackSaveFailureRedirectsExchange() throws Exception {
        givenState("s1", UUID.randomUUID());
        expectExchange(installed("T1", "").replace("\"UBOT\"", "\"" + "U".repeat(40) + "\""));

        callback("s1", NONCE)
            .andExpect(status().isFound())
            .andExpect(header().string("Location", FRONT + "error=slack_exchange"));

        assertThat(installationRepository.count()).isZero();
        assertThat(linkRepository.count()).isZero();
    }
}
