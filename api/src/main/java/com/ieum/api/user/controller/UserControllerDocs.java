package com.ieum.api.user.controller;

import com.ieum.api.user.dto.ChangePasswordRequest;
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

    @Operation(summary = "비밀번호 변경", description = """
        로그인 상태에서 **현재 비밀번호를 확인한 뒤** 새 비밀번호로 바꾼다. LOCAL(이메일 가입) 계정만 가능하다.
        비밀번호를 잊은 경우는 `POST /api/v1/auth/password/reset`(이메일 인증)을 쓴다.

        성공하면 Refresh Token이 삭제되어 **현재 기기를 포함한 모든 기기의 세션이 끊긴다** (이미 발급된 Access Token은 만료까지 유효).
        FE는 성공 즉시 토큰을 지우고 로그인 화면으로 보낸다.

        에러 (현재 비밀번호 불일치도 401이 아닌 400):
        - `CURRENT_PASSWORD_MISMATCH`(400) — 현재 비밀번호 불일치
        - `PASSWORD_SAME_AS_CURRENT`(400) — 새 비밀번호가 현재와 같음
        - `PASSWORD_CHANGE_NOT_SUPPORTED`(400) — Google 등 소셜 가입 계정
        - `INVALID_INPUT`(400) — 새 비밀번호 규칙 위반
        """)
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<Void>> changePassword(CustomUserDetails userDetails, ChangePasswordRequest request);

    @Operation(summary = "회원 탈퇴")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<Void>> deleteMyAccount(CustomUserDetails userDetails);
}
