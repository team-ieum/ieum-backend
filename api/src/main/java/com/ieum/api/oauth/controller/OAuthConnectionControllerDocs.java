package com.ieum.api.oauth.controller;

import com.ieum.api.oauth.dto.OAuthConnectionResponse;
import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
        + "DISCORD 항목은 연결한 서버마다 1개 — id=서버 링크 ID, providerAccountId=서버(guild) ID, displayName=연결 시점 서버 이름, scopes=null. "
        + "다른 제공자의 displayName은 null입니다.")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<List<OAuthConnectionResponse>>> getConnections(CustomUserDetails userDetails);

    @Operation(summary = "OAuth 연동 해제", description = """
        해당 provider 연동을 해제한다. 저장된 Access·Refresh Token을 **즉시 영구 삭제**한다.

        - 해제 후 이 서비스를 쓰는 워크플로우 노드는 실행 시 `ACCOUNT_NOT_CONNECTED`로 실패한다 (워크플로우는 자동 비활성화되지 않음).
          FE는 해제 전에 `GET /api/v1/integrations/{serviceType}/workflows`로 영향받는 워크플로우를 보여주고 확인을 받는다
        - Google로 가입한 사용자도 Google 로그인은 계속 된다. 다음 Google 로그인 때 기본 로그인 권한(email·profile)으로만 다시 연결되고,
          Gmail·Drive 등 추가 권한은 다시 동의해야 한다
        - 해제 후 다시 연동할 수 있다

        에러:
        - `ACCOUNT_NOT_CONNECTED`(400) — 연동되지 않은 provider
        - `INVALID_INPUT`(400) — `LOCAL` 또는 지원하지 않는 provider 값

        **Slack은 이 API가 아니다.** 목록의 `SLACK` 항목은 `DELETE /api/v1/slack/oauth2/connection`으로 해제한다
        (워크스페이스 단위 설치라 해제 방식이 다르다 — 이 경로에 `SLACK`을 보내면 `INVALID_INPUT`)

        **Discord도 이 API가 아니다.** `DISCORD` 항목은 `DELETE /api/v1/discord/oauth2/connections/{providerAccountId}`로
        서버마다 해제한다.
        """)
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<Void>> disconnect(
        CustomUserDetails userDetails,
        @Parameter(description = "해제할 provider", example = "GOOGLE") AuthProvider provider);
}
