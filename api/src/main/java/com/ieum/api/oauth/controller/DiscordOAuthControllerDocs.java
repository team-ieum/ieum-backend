package com.ieum.api.oauth.controller;

import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag(name = "Discord OAuth", description = "Discord 봇 연결(OAuth2 봇 설치) API")
public interface DiscordOAuthControllerDocs {

    @Operation(summary = "Discord 봇 설치 URL 반환",
        description = "Discord 봇 설치(bot+identify, 권한 275146460176) 인가 URL을 돌려준다. 응답에 HttpOnly 쿠키 "
            + "__Host-discord_oauth_nonce(Path=/, Domain 없음, Secure, SameSite=Lax, 300초)를 심는다 — 콜백은 이 쿠키가 있는 "
            + "같은 브라우저에서만 완료된다. 프런트는 이 요청을 같은 출처로 보내거나 credentials: 'include'로 보내야 쿠키가 저장된다. "
            + "5분 안에 설치를 끝내야 한다. 설치에는 그 Discord 서버의 서버 관리 권한이 필요하다. 서버마다 한 번씩 거친다.")
    @SecurityRequirement(name = "BearerAuth")
    @PreAuthorize("hasRole('USER')")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인가 URL 반환 + nonce 쿠키"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요")
    })
    ResponseEntity<ApiResponse<Map<String, String>>> authorize(CustomUserDetails userDetails);

    @Operation(summary = "Discord 설치 콜백",
        description = "Discord가 리다이렉트하는 콜백(로그인 불필요). 결과는 프런트 연동 화면으로 302 — 성공 ?connected=discord, "
            + "실패 ?error=discord_state_expired(state 없음·만료·그새 탈퇴) | discord_state_mismatch(설치를 시작한 브라우저가 아님) | "
            + "discord_denied(취소) | discord_exchange(코드 교환 실패·설치된 서버 없음·저장 실패). nonce 쿠키는 지운다. "
            + "Discord가 붙이는 guild_id·permissions·iss 쿼리는 쓰지 않는다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "302", description = "프런트로 리다이렉트(성공·실패 모두)")
    })
    ResponseEntity<Void> callback(String state, String code, String error, String nonce);

    @Operation(summary = "Discord 서버 연결 해제",
        description = "내 그 서버 링크만 지운다(없어도 204). 그 서버를 쓰는 IEUM 사용자가 더 없으면 봇이 서버를 나간다. "
            + "해제 뒤 그 서버 채널로 보내는 Discord 봇 노드는 실행 때 실패한다(저장된 워크플로우는 그대로).")
    @SecurityRequirement(name = "BearerAuth")
    @PreAuthorize("hasRole('USER')")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "해제 완료(연결이 없어도)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요")
    })
    ResponseEntity<Void> disconnect(
        CustomUserDetails userDetails,
        @Parameter(description = "해제할 서버 ID(연결 목록의 DISCORD 항목 providerAccountId)", example = "111111111111111111")
        String guildId);
}
