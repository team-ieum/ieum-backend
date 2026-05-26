package com.ieum.api.oauth.controller;

import com.ieum.api.oauth.service.GitHubOAuthService;
import com.ieum.auth.domain.GitHubOAuthState;
import com.ieum.auth.repository.GitHubOAuthStateRepository;
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
@RequestMapping("/api/v1/github/oauth2")
@RequiredArgsConstructor
public class GitHubOAuthController implements GitHubOAuthControllerDocs {

    private final GitHubOAuthService gitHubOAuthService;
    private final GitHubOAuthStateRepository gitHubOAuthStateRepository;

    @Value("${github.app.frontend-redirect-uri}")
    private String frontendRedirectUri;

    @GetMapping("/authorize")
    public ResponseEntity<ApiResponse<Map<String, String>>> authorize(
        @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        String authUrl = gitHubOAuthService.generateAuthUrl(userDetails.getId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of("authUrl", authUrl)));
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
        @RequestParam(required = false) String code,
        @RequestParam String state,
        @RequestParam(required = false) String error
    ) {
        var oAuthStateOpt = gitHubOAuthStateRepository.findById(state);
        if (oAuthStateOpt.isEmpty()) {
            log.warn("[GitHubOAuthController] invalid or expired state — state: {}", state);
            return redirect(frontendRedirectUri + "?error=github_state_expired");
        }
        var oAuthState = oAuthStateOpt.get();

        if (error != null || code == null || code.isBlank()) {
            log.warn("[GitHubOAuthController] GitHub OAuth failed — error: {}, userId: {}",
                error, oAuthState.getUserId());
            gitHubOAuthStateRepository.delete(oAuthState);
            return redirect(frontendRedirectUri + "?error=github_oauth_failed");
        }

        gitHubOAuthStateRepository.delete(oAuthState);

        try {
            gitHubOAuthService.handleCallback(code, oAuthState.getUserId());
        } catch (Exception e) {
            log.error("[GitHubOAuthController] token exchange failed — userId: {}",
                oAuthState.getUserId(), e);
            return redirect(frontendRedirectUri + "?error=github_token_failed");
        }

        return redirect(frontendRedirectUri);
    }

    private ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, url)
            .build();
    }
}
