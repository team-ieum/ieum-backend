package com.ieum.api.oauth.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ieum.auth.domain.AuthProvider;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class OAuthTokenRevokerTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final OAuthTokenRevoker revoker = new OAuthTokenRevoker(restTemplate);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(revoker, "notionClientId", "n-id");
        ReflectionTestUtils.setField(revoker, "notionClientSecret", "n-secret");
        ReflectionTestUtils.setField(revoker, "githubClientId", "gh-id");
        ReflectionTestUtils.setField(revoker, "githubClientSecret", "gh-secret");
    }

    @Test
    void google_refreshToken이_있으면_그것을_form_body로_폐기한다() {
        server.expect(requestTo(OAuthTokenRevoker.GOOGLE_REVOKE_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().formDataContains(Map.of("token", "refresh")))
            .andRespond(withSuccess());

        revoker.revoke(AuthProvider.GOOGLE, "access", "refresh");

        server.verify();
    }

    @Test
    void google_refreshToken이_없으면_accessToken을_폐기한다() {
        server.expect(requestTo(OAuthTokenRevoker.GOOGLE_REVOKE_URL))
            .andExpect(content().formDataContains(Map.of("token", "access")))
            .andRespond(withSuccess());

        revoker.revoke(AuthProvider.GOOGLE, "access", null);

        server.verify();
    }

    @Test
    void notion은_Basic_인증과_버전_헤더로_토큰을_폐기한다() {
        server.expect(requestTo(OAuthTokenRevoker.NOTION_REVOKE_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", basic("n-id", "n-secret")))
            .andExpect(header("Notion-Version", OAuthTokenRevoker.NOTION_VERSION))
            .andExpect(content().json("{\"token\":\"access\"}"))
            .andRespond(withSuccess());

        revoker.revoke(AuthProvider.NOTION, "access", null);

        server.verify();
    }

    @Test
    void github는_앱_승인_grant를_DELETE로_폐기한다() {
        server.expect(requestTo("https://api.github.com/applications/gh-id/grant"))
            .andExpect(method(HttpMethod.DELETE))
            .andExpect(header("Authorization", basic("gh-id", "gh-secret")))
            .andExpect(content().json("{\"access_token\":\"access\"}"))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));

        revoker.revoke(AuthProvider.GITHUB, "access", "refresh");

        server.verify();
    }

    @Test
    void 외부가_실패해도_예외를_던지지_않는다() {
        server.expect(requestTo(OAuthTokenRevoker.NOTION_REVOKE_URL)).andRespond(withServerError());

        assertThatCode(() -> revoker.revoke(AuthProvider.NOTION, "access", null)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void 토큰이_없으면_요청하지_않는다() {
        revoker.revoke(AuthProvider.GITHUB, null, null);
        revoker.revoke(AuthProvider.LOCAL, "access", null);

        server.verify();  // 기대한 요청이 없으니 어떤 요청이든 오면 실패한다
    }

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }
}
