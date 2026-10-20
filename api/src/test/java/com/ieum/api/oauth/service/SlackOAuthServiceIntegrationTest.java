package com.ieum.api.oauth.service;

import static com.ieum.api.oauth.SlackOAuthConfigTest.D3;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.auth.domain.SlackOAuthState;
import com.ieum.auth.repository.SlackOAuthStateRepository;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MultiValueMap;
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
}
