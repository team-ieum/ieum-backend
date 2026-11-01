package com.ieum.api.oauth.controller;

import com.ieum.api.oauth.service.SlackDisconnectService;
import com.ieum.api.oauth.service.SlackOAuthService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/slack/oauth2")
@RequiredArgsConstructor
public class SlackOAuthController implements SlackOAuthControllerDocs {

    /** __Host- 접두사: Secure·Path=/·Domain 없음을 브라우저가 강제 — 형제 서브도메인이 같은 이름 쿠키를 심지 못한다 */
    static final String NONCE_COOKIE = "__Host-slack_oauth_nonce";
    private static final String COOKIE_PATH = "/";

    private final SlackOAuthService slackOAuthService;
    private final SlackDisconnectService slackDisconnectService;

    @Value("${slack.oauth.frontend-redirect-uri}")
    private String frontendRedirectUri;

    @GetMapping("/authorize")
    public ResponseEntity<ApiResponse<Map<String, String>>> authorize(
        @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        SlackOAuthService.Authorization authorization = slackOAuthService.authorize(userDetails.getId());
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, nonceCookie(authorization.nonce(), SlackOAuthService.STATE_TTL_SECONDS))
            .body(ApiResponse.ok(Map.of("authUrl", authorization.authUrl())));
    }

    /** state는 필수가 아니다 — 없으면 400 화이트 페이지 대신 연동 화면으로 "만료"를 돌려준다 */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
        @RequestParam(required = false) String state,
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String error,
        @CookieValue(name = NONCE_COOKIE, required = false) String nonce
    ) {
        String result = slackOAuthService.handleCallback(state, nonce, code, error);
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, frontendRedirectUri + "?" + result)
            .header(HttpHeaders.SET_COOKIE, nonceCookie("", 0))
            .build();
    }

    @DeleteMapping("/connection")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal CustomUserDetails userDetails) {
        slackDisconnectService.disconnect(userDetails.getId());
        return ResponseEntity.noContent().build();
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
