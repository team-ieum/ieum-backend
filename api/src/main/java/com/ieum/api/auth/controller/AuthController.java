package com.ieum.api.auth.controller;

import com.ieum.api.auth.dto.LoginRequest;
import com.ieum.api.auth.dto.OAuthTokenExchangeRequest;
import com.ieum.api.auth.dto.RefreshRequest;
import com.ieum.api.auth.dto.RegisterRequest;
import com.ieum.api.auth.dto.RegisterResponse;
import com.ieum.api.auth.dto.TokenResponse;
import com.ieum.auth.domain.User;
import com.ieum.auth.dto.TokenInfo;
import com.ieum.auth.service.AuthService;
import com.ieum.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController implements AuthControllerDocs {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(
        @RequestBody @Valid RegisterRequest request) {

        User user = authService.register(request.getEmail(), request.getPassword(),
            request.getName());
        return ResponseEntity.status(201).body(ApiResponse.created(RegisterResponse.from(user)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(
        @RequestBody @Valid LoginRequest request) {

        TokenInfo tokenInfo = authService.login(request.getEmail(), request.getPassword());
        return ResponseEntity.ok(ApiResponse.ok(toTokenResponse(tokenInfo)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
        @RequestBody @Valid RefreshRequest request) {

        TokenInfo tokenInfo = authService.refresh(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.ok(toTokenResponse(tokenInfo)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
        @RequestBody @Valid RefreshRequest request) {

        authService.logout(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PostMapping("/token")
    public ResponseEntity<ApiResponse<TokenResponse>> exchangeOAuthToken(
        @RequestBody @Valid OAuthTokenExchangeRequest request) {

        TokenInfo tokenInfo = authService.exchangeOAuthCode(request.getCode());
        return ResponseEntity.ok(ApiResponse.ok(toTokenResponse(tokenInfo)));
    }

    private TokenResponse toTokenResponse(TokenInfo tokenInfo) {
        return TokenResponse.builder()
            .accessToken(tokenInfo.accessToken())
            .refreshToken(tokenInfo.refreshToken())
            .expiresIn(tokenInfo.expiresIn())
            .build();
    }
}
