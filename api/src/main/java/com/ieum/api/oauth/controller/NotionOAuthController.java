package com.ieum.api.oauth.controller;

import com.ieum.api.oauth.service.NotionOAuthService;
import com.ieum.auth.domain.NotionOAuthState;
import com.ieum.auth.repository.NotionOAuthStateRepository;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import java.util.Map;
import java.util.Optional;
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


@Slf4j
@RestController
@RequestMapping("/api/v1/notion/oauth2")
@RequiredArgsConstructor
public class NotionOAuthController implements NotionOAuthControllerDocs {

    private final NotionOAuthService notionOAuthService;
    private final NotionOAuthStateRepository notionOAuthStateRepository;

    @Value("${notion.oauth.frontend-redirect-uri}")
    private String frontendRedirectUri;

    @GetMapping("/authorize")
    public ResponseEntity<ApiResponse<Map<String, String>>> authorizeNotion(
        @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        String authUrl = notionOAuthService.generateAuthUrl(userDetails.getId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of("authUrl", authUrl)));
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
