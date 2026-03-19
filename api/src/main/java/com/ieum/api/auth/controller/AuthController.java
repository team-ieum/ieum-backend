package com.ieum.api.auth.controller;

import com.ieum.api.auth.dto.LoginRequest;
import com.ieum.api.auth.dto.RefreshRequest;
import com.ieum.api.auth.dto.RegisterRequest;
import com.ieum.api.auth.dto.RegisterResponse;
import com.ieum.api.auth.dto.TokenResponse;
import com.ieum.auth.domain.User;
import com.ieum.auth.dto.TokenInfo;
import com.ieum.auth.service.AuthService;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "회원가입, 로그인, 토큰 갱신, 로그아웃")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "회원가입")
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(
        @RequestBody @Valid RegisterRequest request) {

        User user = authService.register(request.getEmail(), request.getPassword(),
            request.getName());
        return ResponseEntity.status(201).body(ApiResponse.created(RegisterResponse.from(user)));
    }

    @Operation(summary = "로그인")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(
        @RequestBody @Valid LoginRequest request) {

        TokenInfo tokenInfo = authService.login(request.getEmail(), request.getPassword());
        return ResponseEntity.ok(ApiResponse.ok(toTokenResponse(tokenInfo)));
    }

    @Operation(summary = "토큰 갱신")
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
        @RequestBody @Valid RefreshRequest request) {

        TokenInfo tokenInfo = authService.refresh(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.ok(toTokenResponse(tokenInfo)));
    }

    @Operation(summary = "로그아웃")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
        @RequestBody @Valid RefreshRequest request) {

        authService.logout(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    private TokenResponse toTokenResponse(TokenInfo tokenInfo) {
        return TokenResponse.builder()
            .accessToken(tokenInfo.accessToken())
            .refreshToken(tokenInfo.refreshToken())
            .expiresIn(tokenInfo.expiresIn())
            .build();
    }
}
