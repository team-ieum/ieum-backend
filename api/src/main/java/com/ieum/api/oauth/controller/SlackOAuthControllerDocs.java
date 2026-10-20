package com.ieum.api.oauth.controller;

import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag(name = "Slack OAuth", description = "Slack 봇 연결(OAuth v2 설치) API")
public interface SlackOAuthControllerDocs {

    @Operation(summary = "Slack 설치 URL 반환",
        description = "Slack 설치(봇 토큰) 인가 URL을 돌려준다. 응답에 HttpOnly 쿠키 slack_oauth_nonce"
            + "(Path=/api/v1/slack/oauth2, Secure, SameSite=Lax, 300초)를 심는다 — 콜백은 이 쿠키가 있는 같은 브라우저에서만 완료된다. "
            + "프런트는 이 요청을 같은 출처로 보내거나 credentials: 'include'로 보내야 쿠키가 저장된다. 5분 안에 설치를 끝내야 한다.")
    @SecurityRequirement(name = "BearerAuth")
    @PreAuthorize("hasRole('USER')")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인가 URL 반환 + nonce 쿠키"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요")
    })
    ResponseEntity<ApiResponse<Map<String, String>>> authorize(CustomUserDetails userDetails);

    @Operation(summary = "Slack 설치 콜백",
        description = "Slack이 리다이렉트하는 콜백(로그인 불필요). 결과는 프런트 연동 화면으로 302 — 성공 ?connected=slack, "
            + "실패 ?error=slack_state_expired(state 없음·만료) | slack_state_mismatch(설치를 시작한 브라우저가 아님) | "
            + "slack_denied(취소) | slack_exchange(코드 교환 실패) | slack_org_install(Enterprise 조직 설치·봇이 아닌 토큰) | "
            + "slack_other_team(다른 워크스페이스에 이미 연결 — 먼저 연결을 해제). nonce 쿠키는 지운다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "302", description = "프런트로 리다이렉트(성공·실패 모두)")
    })
    ResponseEntity<Void> callback(String state, String code, String error, String nonce);
}
