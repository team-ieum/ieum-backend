package com.ieum.api.oauth.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.api.oauth.service.SlackDisconnectService;
import com.ieum.api.oauth.service.SlackOAuthService;
import com.ieum.auth.security.CustomUserDetails;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SlackOAuthControllerTest {

    @Mock private SlackOAuthService slackOAuthService;
    @Mock private SlackDisconnectService slackDisconnectService;
    @InjectMocks private SlackOAuthController slackOAuthController;

    private MockMvc mockMvc;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(slackOAuthController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();

        CustomUserDetails userDetails = CustomUserDetails.of(userId, "user@example.com", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        ReflectionTestUtils.setField(slackOAuthController, "frontendRedirectUri", "http://localhost:3000/settings/integrations");
    }

    @Test
    @DisplayName("authorize — authUrl을 주고 nonce를 HttpOnly·Secure·SameSite=Lax 쿠키(Path=/, 300초)로 심는다")
    void authorizeSetsNonceCookie() throws Exception {
        given(slackOAuthService.authorize(userId))
            .willReturn(new SlackOAuthService.Authorization("https://slack.com/oauth/v2/authorize?state=s1", "n-1"));

        mockMvc.perform(get("/api/v1/slack/oauth2/authorize"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.authUrl").value("https://slack.com/oauth/v2/authorize?state=s1"))
            .andExpect(header().string("Set-Cookie", allOf(
                containsString("__Host-slack_oauth_nonce=n-1"),
                containsString("Path=/;"),
                containsString("Max-Age=300"),
                containsString("HttpOnly"),
                containsString("Secure"),
                containsString("SameSite=Lax"))));
    }

    @Test
    @DisplayName("authorize — nonce 쿠키는 __Host- 규칙(Secure·Path=/·Domain 없음)을 지켜 형제 서브도메인 쿠키 심기를 막는다")
    void authorizeNonceCookieFollowsHostPrefixRules() throws Exception {
        given(slackOAuthService.authorize(userId))
            .willReturn(new SlackOAuthService.Authorization("https://slack.com/oauth/v2/authorize?state=s1", "n-1"));

        mockMvc.perform(get("/api/v1/slack/oauth2/authorize"))
            .andExpect(header().string("Set-Cookie", allOf(
                startsWith("__Host-"),
                containsString("; Path=/;"),
                not(containsStringIgnoringCase("Domain=")),
                containsString("Secure"))));
    }

    @Test
    @DisplayName("callback — 쿠키 nonce를 서비스에 넘기고 결과 쿼리로 302, nonce 쿠키는 Max-Age=0으로 지운다")
    void callbackRedirectsAndClearsCookie() throws Exception {
        given(slackOAuthService.handleCallback("s1", "n-1", "c1", null)).willReturn("connected=slack");

        mockMvc.perform(get("/api/v1/slack/oauth2/callback")
                .param("state", "s1").param("code", "c1")
                .cookie(new Cookie("__Host-slack_oauth_nonce", "n-1")))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "http://localhost:3000/settings/integrations?connected=slack"))
            .andExpect(header().string("Set-Cookie", allOf(
                containsString("__Host-slack_oauth_nonce="),
                containsString("Path=/;"),
                containsString("Max-Age=0"))));
    }

    @Test
    @DisplayName("DELETE /connection — 본인 링크 해제를 부르고 204")
    void disconnectReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/slack/oauth2/connection"))
            .andExpect(status().isNoContent());

        verify(slackDisconnectService).disconnect(userId);
    }
}
