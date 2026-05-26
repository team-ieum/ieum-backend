package com.ieum.api.oauth.controller;

import com.ieum.api.oauth.service.NotionOAuthService;
import com.ieum.auth.domain.NotionOAuthState;
import com.ieum.auth.repository.NotionOAuthStateRepository;
import com.ieum.auth.security.CustomUserDetails;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@RestController
@RequestMapping("/api/v1/notion/oauth2")
@RequiredArgsConstructor
public class NotionOAuthController implements NotionOAuthControllerDocs {

    private static final long OAUTH_STATE_TTL_SECONDS = 300L;

    private final NotionOAuthService notionOAuthService;
    private final NotionOAuthStateRepository notionOAuthStateRepository;

    @Value("${notion.oauth.auth-url}")
    private String notionAuthUrl;

    @Value("${notion.oauth.client-id}")
    private String notionClientId;

    @Value("${notion.oauth.redirect-uri}")
    private String notionRedirectUri;

    @Value("${notion.oauth.frontend-redirect-uri}")
    private String frontendRedirectUri;

    @GetMapping("/authorize")
    public ResponseEntity<Void> authorizeNotion(
        @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        String state = UUID.randomUUID().toString();
        notionOAuthStateRepository.save(
            NotionOAuthState.builder()
                .state(state)
                .userId(userDetails.getId())
                .ttl(OAUTH_STATE_TTL_SECONDS)
                .build()
        );

        String authUrl = UriComponentsBuilder
            .fromUriString(notionAuthUrl)
            .queryParam("client_id", notionClientId)
            .queryParam("response_type", "code")
            .queryParam("owner", "user")
            .queryParam("redirect_uri", notionRedirectUri)
            .queryParam("state", state)
            .build().toUriString();

        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, authUrl)
            .build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> notionCallback(
        @RequestParam(required = false) String code,
        @RequestParam String state,
        @RequestParam(required = false) String error
    ) {
        Optional<NotionOAuthState> oAuthStateOpt = notionOAuthStateRepository.findById(state);
        if (oAuthStateOpt.isEmpty()) {
            log.warn("[NotionOAuthController] 유효하지 않은 OAuth state — state: {}", state);
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, frontendRedirectUri + "?error=notion_state_expired")
                .build();
        }
        NotionOAuthState oAuthState = oAuthStateOpt.get();

        if (error != null || code == null || code.isBlank()) {
            log.warn("[NotionOAuthController] Notion OAuth 실패 — error: {}, userId: {}",
                error, oAuthState.getUserId());
            notionOAuthStateRepository.delete(oAuthState);
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, frontendRedirectUri + "?error=notion_oauth_failed")
                .build();
        }

        notionOAuthStateRepository.delete(oAuthState);  // 1회성 소비

        try {
            notionOAuthService.handleCallback(code, oAuthState.getUserId());
        } catch (Exception e) {
            log.error("[NotionOAuthController] Notion token 교환 실패 — userId: {}", oAuthState.getUserId(), e);
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, frontendRedirectUri + "?error=notion_token_failed")
                .build();
        }

        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, frontendRedirectUri)
            .build();
    }
}
