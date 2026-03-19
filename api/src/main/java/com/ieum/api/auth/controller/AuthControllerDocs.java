package com.ieum.api.auth.controller;

import com.ieum.api.auth.dto.LoginRequest;
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
}
