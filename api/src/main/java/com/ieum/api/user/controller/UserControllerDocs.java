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

    @Operation(summary = "회원 탈퇴", description = """
        계정과 사용자 데이터를 **즉시 영구 삭제**한다 (복구 불가, 소프트 딜리트 아님).

        삭제 대상: 워크플로우 전체(버전·실행 이력·채팅·스케줄·노드 테스트 샘플·웹훅 수신 주소), AI API Key,
        웹훅 자격증명, MCP 서버, 프롬프트 템플릿, OAuth 연동(Google·Notion·GitHub 토큰), Refresh Token.
        Slack 연결도 같이 해제한다 — 같은 워크스페이스를 쓰는 다른 사용자가 없으면 탈퇴가 커밋된 뒤 봇 토큰을 폐기한다.
        정리 중 오류가 나면 전부 롤백되어 계정도 그대로 남는다.

        이미 발급된 Access Token은 만료(30분)까지 서명상 유효하다. **FE는 성공 즉시 토큰을 버리고 로그인 화면으로 보낸다.**
        Google·Notion·GitHub 제공자 쪽 권한(Google 계정의 앱 연결 등)은 해제하지 않는다.
        """)
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<Void>> deleteMyAccount(CustomUserDetails userDetails);
}
