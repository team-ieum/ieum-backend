package com.ieum.api.oauth.controller;

import com.ieum.api.oauth.service.SlackOAuthService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/slack/oauth2")
@RequiredArgsConstructor
public class SlackOAuthController implements SlackOAuthControllerDocs {

    static final String NONCE_COOKIE = "slack_oauth_nonce";
    /** 콜백 경로에만 실린다 — 다른 API 요청엔 붙지 않는다 */
    private static final String COOKIE_PATH = "/api/v1/slack/oauth2";

    private final SlackOAuthService slackOAuthService;

    @GetMapping("/authorize")
    public ResponseEntity<ApiResponse<Map<String, String>>> authorize(
        @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        SlackOAuthService.Authorization authorization = slackOAuthService.authorize(userDetails.getId());
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, nonceCookie(authorization.nonce(), SlackOAuthService.STATE_TTL_SECONDS))
            .body(ApiResponse.ok(Map.of("authUrl", authorization.authUrl())));
    }

    private static String nonceCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(NONCE_COOKIE, value)
            .path(COOKIE_PATH)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax") // Slack에서 돌아오는 최상위 GET 이동엔 실리고, 다른 사이트의 하위 요청엔 안 실린다
            .maxAge(maxAgeSeconds)
            .build()
            .toString();
    }
}
