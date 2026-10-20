package com.ieum.api.oauth.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.common.GlobalExceptionHandler;
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
    @DisplayName("authorize — authUrl을 주고 nonce를 HttpOnly·Secure·SameSite=Lax 쿠키(콜백 경로, 300초)로 심는다")
    void authorizeSetsNonceCookie() throws Exception {
        given(slackOAuthService.authorize(userId))
            .willReturn(new SlackOAuthService.Authorization("https://slack.com/oauth/v2/authorize?state=s1", "n-1"));

        mockMvc.perform(get("/api/v1/slack/oauth2/authorize"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.authUrl").value("https://slack.com/oauth/v2/authorize?state=s1"))
            .andExpect(header().string("Set-Cookie", allOf(
                containsString("slack_oauth_nonce=n-1"),
                containsString("Path=/api/v1/slack/oauth2"),
                containsString("Max-Age=300"),
                containsString("HttpOnly"),
                containsString("Secure"),
                containsString("SameSite=Lax"))));
    }

    @Test
    @DisplayName("callback — 쿠키 nonce를 서비스에 넘기고 결과 쿼리로 302, nonce 쿠키는 Max-Age=0으로 지운다")
    void callbackRedirectsAndClearsCookie() throws Exception {
        given(slackOAuthService.handleCallback("s1", "n-1", "c1", null)).willReturn("connected=slack");

        mockMvc.perform(get("/api/v1/slack/oauth2/callback")
                .param("state", "s1").param("code", "c1")
                .cookie(new Cookie("slack_oauth_nonce", "n-1")))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "http://localhost:3000/settings/integrations?connected=slack"))
            .andExpect(header().string("Set-Cookie", allOf(
                containsString("slack_oauth_nonce="),
                containsString("Path=/api/v1/slack/oauth2"),
                containsString("Max-Age=0"))));
    }
}
