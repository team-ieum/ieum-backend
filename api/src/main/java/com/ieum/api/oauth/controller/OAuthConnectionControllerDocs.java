package com.ieum.api.oauth.controller;

import com.ieum.api.oauth.dto.OAuthConnectionResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag(name = "OAuth Connections", description = "OAuth 연동 계정 관리")
@SecurityRequirement(name = "BearerAuth")
public interface OAuthConnectionControllerDocs {

    @Operation(summary = "연동된 OAuth 계정 목록 조회", description = "현재 로그인한 사용자의 연동된 OAuth 계정 목록을 조회합니다. "
        + "SLACK 항목은 id=Slack 링크 ID, providerAccountId=워크스페이스(team) ID, displayName=워크스페이스 이름, scopes=봇 scope. "
        + "다른 제공자의 displayName은 null입니다.")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<List<OAuthConnectionResponse>>> getConnections(CustomUserDetails userDetails);
}
