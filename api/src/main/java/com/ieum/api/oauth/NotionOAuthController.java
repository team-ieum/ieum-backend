package com.ieum.api.oauth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import com.ieum.auth.security.CustomUserDetails;

@RestController
@RequestMapping("/api/v1/oauth2")
@RequiredArgsConstructor
public class NotionOAuthController {

    private final NotionOAuthService notionOAuthService;

    @Value("${notion.oauth.client-id}")
    private String notionClientId;

    @Value("${notion.oauth.redirect-uri}")
    private String notionRedirectUri;

    @Value("${notion.oauth.frontend-redirect-uri}")
    private String frontendRedirectUri;

    /**
     * Notion OAuth 인가 URL로 리다이렉트한다.
     * GET /api/v1/oauth2/authorize/notion
     */
    @GetMapping("/authorize/notion")
    public ResponseEntity<Void> authorizeNotion() {
        String authUrl = UriComponentsBuilder
            .fromUriString("https://api.notion.com/v1/oauth/authorize")
            .queryParam("client_id", notionClientId)
            .queryParam("response_type", "code")
            .queryParam("owner", "user")
            .queryParam("redirect_uri", notionRedirectUri)
            .build().toUriString();

        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, authUrl)
            .build();
    }

    /**
     * Notion OAuth Callback을 처리한다.
     * code를 access_token으로 교환하고 connected_accounts에 저장한다.
     * GET /api/v1/oauth2/callback/notion
     * 이 엔드포인트는 JWT 인증이 필요하다 (로그인한 사용자가 Notion을 연동하는 흐름).
     */
    @GetMapping("/callback/notion")
    public ResponseEntity<Void> notionCallback(
        @RequestParam String code,
        @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        notionOAuthService.handleCallback(code, userDetails.getId());

        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, frontendRedirectUri)
            .build();
    }
}
