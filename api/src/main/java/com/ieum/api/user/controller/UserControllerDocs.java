package com.ieum.api.user.controller;

import com.ieum.api.user.dto.UpdateUserRequest;
import com.ieum.api.user.dto.UserResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag(name = "사용자", description = "사용자 프로필 관리")
@SecurityRequirement(name = "BearerAuth")
public interface UserControllerDocs {

    @Operation(summary = "내 정보 조회")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<UserResponse>> getMyProfile(CustomUserDetails userDetails);

    @Operation(summary = "내 정보 수정")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<UserResponse>> updateMyProfile(CustomUserDetails userDetails, UpdateUserRequest request);

    @Operation(summary = "회원 탈퇴", description = "계정을 삭제한다. Slack 연결도 같이 해제한다 — 같은 워크스페이스를 쓰는 다른 사용자가 없으면 탈퇴가 커밋된 뒤 봇 토큰을 폐기한다.")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<Void>> deleteMyAccount(CustomUserDetails userDetails);
}
