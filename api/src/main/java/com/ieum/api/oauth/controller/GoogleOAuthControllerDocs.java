package com.ieum.api.oauth.controller;

import com.ieum.api.oauth.dto.AvailableScopesResponse;
import com.ieum.api.oauth.dto.ConnectAccountResponse;
import com.ieum.api.oauth.dto.MyScopesResponse;
import com.ieum.api.oauth.dto.ScopeRequestRequest;
import com.ieum.api.oauth.dto.ScopeRequestResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestBody;

@Tag(name = "Google OAuth", description = "Google OAuth scope 관리 API")
public interface GoogleOAuthControllerDocs {

    @Operation(
        summary = "지원 scope 목록 조회",
        description = "IEUM에서 사용 가능한 Google scope 그룹과 각 그룹의 scope URL 목록을 반환합니다."
    )
    ResponseEntity<ApiResponse<AvailableScopesResponse>> getAvailableScopes();

    @Operation(
        summary = "내 scope 목록 조회",
        description = "현재 인증된 사용자가 Google에 승인한 scope 목록을 반환합니다."
    )
    ResponseEntity<ApiResponse<MyScopesResponse>> getMyScopes(
        @AuthenticationPrincipal CustomUserDetails userDetails
    );

    @Operation(
        summary = "추가 scope 권한 요청",
        description = "요청한 scope 그룹 중 아직 보유하지 않은 scope를 확인하고, "
            + "재인증이 필요한 경우 Google OAuth 인증 URL을 반환합니다. "
            + "이미 모든 scope를 보유한 경우 requiresAuth=false로 응답합니다."
    )
    ResponseEntity<ApiResponse<ScopeRequestResponse>> requestScope(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @RequestBody ScopeRequestRequest request
    );

    @Operation(
        summary = "Google OAuth 인증 URL 조회",
        description = "Google OAuth 재인증(scope 추가)을 위한 URL을 반환합니다. "
            + "클라이언트는 이 URL로 리다이렉트하여 Google 동의 화면을 진행합니다."
    )
    ResponseEntity<ApiResponse<Map<String, String>>> getAuthorizeUrl();

    @Operation(
        summary = "Google 계정 연동",
        description = "현재 로그인된 계정에 Google을 연동하기 위한 OAuth URL을 발급합니다. "
            + "소셜 회원가입 여부와 무관하게 기존 계정에 Google scope를 연결하며, "
            + "응답의 authorizationUrl로 리다이렉트하면 동의 후 현재 계정에 연동됩니다."
    )
    ResponseEntity<ApiResponse<ConnectAccountResponse>> connect(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @RequestBody ScopeRequestRequest request
    );
}
