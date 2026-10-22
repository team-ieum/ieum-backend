package com.ieum.api.oauth.controller;

import com.ieum.api.oauth.service.DiscordDisconnectService;
import com.ieum.api.oauth.service.DiscordOAuthService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/discord/oauth2")
@RequiredArgsConstructor
public class DiscordOAuthController implements DiscordOAuthControllerDocs {

    /** __Host- 접두사: Secure·Path=/·Domain 없음을 브라우저가 강제 — 형제 서브도메인이 같은 이름 쿠키를 심지 못한다 */
    static final String NONCE_COOKIE = "__Host-discord_oauth_nonce";
    private static final String COOKIE_PATH = "/";

    private final DiscordOAuthService discordOAuthService;
    private final DiscordDisconnectService discordDisconnectService;

    @Value("${discord.oauth.frontend-redirect-uri}")
    private String frontendRedirectUri;

    @GetMapping("/authorize")
    public ResponseEntity<ApiResponse<Map<String, String>>> authorize(
        @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        DiscordOAuthService.Authorization authorization = discordOAuthService.authorize(userDetails.getId());
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, nonceCookie(authorization.nonce(), DiscordOAuthService.STATE_TTL_SECONDS))
            .body(ApiResponse.ok(Map.of("authUrl", authorization.authUrl())));
    }

    @DeleteMapping("/connections/{guildId}")
    public ResponseEntity<Void> disconnect(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @PathVariable String guildId
    ) {
        discordDisconnectService.disconnect(userDetails.getId(), guildId);
        return ResponseEntity.noContent().build();
    }

    static String nonceCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(NONCE_COOKIE, value)
            .path(COOKIE_PATH)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax") // Discord에서 돌아오는 최상위 GET 이동엔 실리고, 다른 사이트의 하위 요청엔 안 실린다
            .maxAge(maxAgeSeconds)
            .build()
            .toString();
    }
}
