package com.ieum.api.user.controller;

import com.ieum.api.user.dto.ChangePasswordRequest;
import com.ieum.api.user.dto.UpdateUserRequest;
import com.ieum.api.user.dto.UserResponse;
import com.ieum.api.user.service.UserService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.auth.service.AuthService;
import com.ieum.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController implements UserControllerDocs {

    private final UserService userService;
    private final AuthService authService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMyProfile(
        @AuthenticationPrincipal CustomUserDetails userDetails) {

        return ResponseEntity.ok(ApiResponse.ok(userService.getMyProfile(userDetails.getId())));
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> updateMyProfile(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @RequestBody @Valid UpdateUserRequest request) {

        return ResponseEntity.ok(
            ApiResponse.ok(userService.updateMyProfile(userDetails.getId(), request.getName())));
    }

    @PatchMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @RequestBody @Valid ChangePasswordRequest request) {

        authService.changePassword(userDetails.getId(), request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> deleteMyAccount(
        @AuthenticationPrincipal CustomUserDetails userDetails) {

        userService.deleteMyAccount(userDetails.getId());
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
