package com.ieum.api.oauth.service;

import com.ieum.auth.domain.AuthProvider;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * 연동 해제 시 외부 서비스에 토큰 폐기(revoke)를 요청한다. <b>best-effort</b> — 실패해도 예외를 던지지 않는다.
 * 우리 DB의 토큰은 이미 지워져 우리 쪽 접근은 끊겼으므로, 외부 장애가 해제를 막아선 안 된다.
 *
 * <p>토큰 원문과 응답 본문은 로그에 남기지 않는다(상태 코드·예외 종류만).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuthTokenRevoker {

    static final String GOOGLE_REVOKE_URL = "https://oauth2.googleapis.com/revoke";
    static final String NOTION_REVOKE_URL = "https://api.notion.com/v1/oauth/revoke";
    static final String GITHUB_GRANT_URL = "https://api.github.com/applications/{clientId}/grant";
    /** {@code NotionApiReader.NOTION_VERSION}과 같은 값 — revoke 엔드포인트도 같은 버전 헤더를 받는다. */
    static final String NOTION_VERSION = "2026-03-11";

    private final RestTemplate restTemplate;

    @Value("${notion.oauth.client-id}")
    private String notionClientId;

    @Value("${notion.oauth.client-secret}")
    private String notionClientSecret;

    @Value("${github.app.client-id}")
    private String githubClientId;

    @Value("${github.app.client-secret}")
    private String githubClientSecret;

    /**
     * @param accessToken  복호화된 Access Token (없으면 null)
     * @param refreshToken 복호화된 Refresh Token (없으면 null) — Google은 이것을 폐기하면 승인 전체가 폐기된다
     */
    public void revoke(AuthProvider provider, String accessToken, String refreshToken) {
        try {
            switch (provider) {
                case GOOGLE -> revokeGoogle(refreshToken != null ? refreshToken : accessToken);
                case NOTION -> revokeNotion(accessToken);
                case GITHUB -> revokeGitHub(accessToken);
                default -> { }
            }
        } catch (HttpStatusCodeException e) {
            log.warn("[OAuthTokenRevoker] {} 외부 토큰 폐기 실패 — status: {} (우리 쪽 해제는 완료)",
                provider, e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("[OAuthTokenRevoker] {} 외부 토큰 폐기 실패 — {} (우리 쪽 해제는 완료)",
                provider, e.getClass().getSimpleName());
        }
    }

    /** 토큰을 URL 쿼리가 아닌 form body로 보낸다 — 프록시·접근 로그에 남지 않게. */
    private void revokeGoogle(String token) {
        if (token == null) {
            return;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("token", token);
        restTemplate.postForEntity(GOOGLE_REVOKE_URL, new HttpEntity<>(body, headers), Void.class);
    }

    private void revokeNotion(String token) {
        if (token == null) {
            return;
        }
        HttpHeaders headers = basicAuthJson(notionClientId, notionClientSecret);
        headers.set("Notion-Version", NOTION_VERSION);
        restTemplate.postForEntity(NOTION_REVOKE_URL, new HttpEntity<>(Map.of("token", token), headers), Void.class);
    }

    /** 사용자 승인(grant)을 통째로 폐기한다. GitHub App 설치(installation)는 사용자가 GitHub 설정에서 해제해야 한다. */
    private void revokeGitHub(String token) {
        if (token == null) {
            return;
        }
        HttpHeaders headers = basicAuthJson(githubClientId, githubClientSecret);
        headers.setAccept(List.of(MediaType.parseMediaType("application/vnd.github+json")));
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        restTemplate.exchange(GITHUB_GRANT_URL, HttpMethod.DELETE,
            new HttpEntity<>(Map.of("access_token", token), headers), Void.class, githubClientId);
    }

    private static HttpHeaders basicAuthJson(String clientId, String clientSecret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
            .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8)));
        return headers;
    }
}
