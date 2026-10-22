package com.ieum.api.oauth.service;

import static com.ieum.api.oauth.DiscordOAuthConfigTest.D6;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.auth.domain.DiscordOAuthState;
import com.ieum.auth.repository.DiscordGuildLinkRepository;
import com.ieum.auth.repository.DiscordOAuthStateRepository;
import com.ieum.auth.repository.UserRepository;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
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
}
