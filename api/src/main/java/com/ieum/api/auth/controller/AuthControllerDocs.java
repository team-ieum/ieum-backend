package com.ieum.api.auth.controller;

import com.ieum.api.auth.dto.LoginRequest;
import com.ieum.api.auth.dto.OAuthTokenExchangeRequest;
import com.ieum.api.auth.dto.RefreshRequest;
import com.ieum.api.auth.dto.RegisterRequest;
import com.ieum.api.auth.dto.RegisterResponse;
import com.ieum.api.auth.dto.TokenResponse;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "인증", description = "회원가입, 로그인, 토큰 갱신, 로그아웃")
public interface AuthControllerDocs {

    @Operation(summary = "회원가입")
    ResponseEntity<ApiResponse<RegisterResponse>> register(RegisterRequest request);

    @Operation(summary = "로그인")
    ResponseEntity<ApiResponse<TokenResponse>> login(LoginRequest request);

    @Operation(summary = "토큰 갱신")
    ResponseEntity<ApiResponse<TokenResponse>> refresh(RefreshRequest request);

    @Operation(summary = "로그아웃")
    ResponseEntity<ApiResponse<Void>> logout(RefreshRequest request);

    @Operation(summary = "OAuth 토큰 교환", description = "Google 로그인 후 발급된 일회용 코드를 JWT 토큰으로 교환합니다. 코드는 30초 내에 한 번만 사용 가능합니다.")
    ResponseEntity<ApiResponse<TokenResponse>> exchangeOAuthToken(OAuthTokenExchangeRequest request);
}
