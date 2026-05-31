package com.ieum.api.oauth.controller;

import com.ieum.api.oauth.dto.AvailableScopesResponse;
import com.ieum.api.oauth.dto.ConnectAccountResponse;
import com.ieum.api.oauth.dto.MyScopesResponse;
import com.ieum.api.oauth.dto.ScopeRequestRequest;
import com.ieum.api.oauth.dto.ScopeRequestResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.auth.service.GoogleOAuthService;
import com.ieum.common.dto.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/oauth/google")
@RequiredArgsConstructor
public class GoogleOAuthController implements GoogleOAuthControllerDocs {

    private final GoogleOAuthService googleOAuthService;

    /**
     * GET /api/v1/oauth/google/scopes
     * 지원 가능한 Google scope 그룹 목록을 반환한다.
     */
    @GetMapping("/scopes")
    public ResponseEntity<ApiResponse<AvailableScopesResponse>> getAvailableScopes() {
        return ResponseEntity.ok(
            ApiResponse.ok(AvailableScopesResponse.from(googleOAuthService.getAvailableScopes()))
        );
    }

    /**
     * GET /api/v1/oauth/google/my-scopes
     * 현재 인증된 사용자의 Google scope 목록을 반환한다.
     */
    @GetMapping("/my-scopes")
    public ResponseEntity<ApiResponse<MyScopesResponse>> getMyScopes(
        @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        List<String> scopes = googleOAuthService.getMyScopes(userDetails.getId());
        log.debug("[GoogleOAuthController] my-scopes 조회 — userId: {}, count: {}",
            userDetails.getId(), scopes.size());
        return ResponseEntity.ok(ApiResponse.ok(MyScopesResponse.of(scopes)));
    }

    /**
     * POST /api/v1/oauth/google/request-scope
     * 요청된 scope 그룹 중 누락된 scope를 확인하고, 재인증이 필요하면 인증 URL을 반환한다.
     */
    @PostMapping("/request-scope")
    public ResponseEntity<ApiResponse<ScopeRequestResponse>> requestScope(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @Valid @RequestBody ScopeRequestRequest request
    ) {
        List<String> missingScopes = googleOAuthService.getMissingScopes(
            userDetails.getId(), request.getScopeGroups());

        if (missingScopes.isEmpty()) {
            log.debug("[GoogleOAuthController] 모든 scope 보유 — userId: {}", userDetails.getId());
            return ResponseEntity.ok(ApiResponse.ok(ScopeRequestResponse.noAuthRequired()));
        }

        String authorizationUrl = googleOAuthService.getAuthorizationUrl(request.getScopeGroups());
        log.info("[GoogleOAuthController] scope 재인증 필요 — userId: {}, missing: {}",
            userDetails.getId(), missingScopes);
        return ResponseEntity.ok(
            ApiResponse.ok(ScopeRequestResponse.authRequired(missingScopes, authorizationUrl))
        );
    }

    /**
     * GET /api/v1/oauth/google/authorize-url
     * Google OAuth 재인증 URL을 반환한다.
     */
    @GetMapping("/authorize-url")
    public ResponseEntity<ApiResponse<Map<String, String>>> getAuthorizeUrl() {
        return ResponseEntity.ok(
            ApiResponse.ok(Map.of("url", googleOAuthService.getAuthorizationUrl()))
        );
    }

    /**
     * POST /api/v1/oauth/google/connect
     * 현재 로그인된 계정에 Google을 연동하기 위한 OAuth URL을 발급한다.
     * 소셜 회원가입 여부와 무관하게 기존 계정에 Google scope를 연결한다.
     */
    @PostMapping("/connect")
    public ResponseEntity<ApiResponse<ConnectAccountResponse>> connect(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @Valid @RequestBody ScopeRequestRequest request
    ) {
        String authorizationUrl = googleOAuthService.startAccountLinking(
            userDetails.getId(), request.getScopeGroups());
        log.info("[GoogleOAuthController] 계정 연동 URL 발급 — userId: {}", userDetails.getId());
        return ResponseEntity.ok(ApiResponse.ok(ConnectAccountResponse.of(authorizationUrl)));
    }
}
