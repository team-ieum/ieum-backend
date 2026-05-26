package com.ieum.api.oauth.controller;

import com.ieum.auth.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "GitHub OAuth", description = "GitHub App 계정 연동 OAuth 2.0 API")
public interface GitHubOAuthControllerDocs {

    @Operation(summary = "GitHub OAuth 인가 URL 리다이렉트",
        description = "GitHub OAuth 인가 페이지로 리다이렉트한다. 로그인이 필요하다.")
    @ApiResponses({
        @ApiResponse(responseCode = "302", description = "GitHub 인가 페이지로 리다이렉트"),
        @ApiResponse(responseCode = "401", description = "인증 필요")
    })
    ResponseEntity<Void> authorize(
        @AuthenticationPrincipal CustomUserDetails userDetails
    );

    @Operation(summary = "GitHub OAuth Callback 처리",
        description = "GitHub 인가 후 callback을 처리한다. code와 state를 검증하고 access_token 및 refresh_token을 저장한다.")
    @ApiResponses({
        @ApiResponse(responseCode = "302", description = "처리 완료 후 프론트엔드로 리다이렉트 (성공 또는 에러 모두 포함)"),
        @ApiResponse(responseCode = "400", description = "유효하지 않은 state 또는 code")
    })
    ResponseEntity<Void> callback(
        @RequestParam(required = false) String code,
        @RequestParam String state,
        @RequestParam(required = false) String error
    );
}
