package com.ieum.api.webhookcredential.controller;

import com.ieum.api.webhookcredential.dto.CreateWebhookCredentialRequest;
import com.ieum.api.webhookcredential.dto.UpdateAlertTargetRequest;
import com.ieum.api.webhookcredential.dto.WebhookCredentialResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.UUID;

@Tag(name = "웹훅 자격증명", description = "Slack/Discord Incoming Webhook URL 등록 및 관리")
@SecurityRequirement(name = "BearerAuth")
public interface WebhookCredentialControllerDocs {

    @Operation(summary = "웹훅 자격증명 등록",
            description = "webhookUrl은 provider의 웹훅 호스트·경로여야 한다(https 전용, 맞지 않으면 400). "
                    + "SLACK은 hooks.slack.com의 /services·/triggers·/workflows, "
                    + "DISCORD는 discord.com·discordapp.com(ptb·canary 서브도메인 포함)의 "
                    + "/api/webhooks 및 /api/v10/webhooks 같은 버전 경로를 받는다.")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<WebhookCredentialResponse>> create(CustomUserDetails userDetails,
                                                                  CreateWebhookCredentialRequest request);

    @Operation(summary = "웹훅 자격증명 목록 조회")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<List<WebhookCredentialResponse>>> getList(CustomUserDetails userDetails);

    @Operation(summary = "실행 실패 알림 대상 지정/해제",
            description = "지정하면 워크플로우 실행 실패 시 이 Discord 웹훅으로 알림을 받는다. "
                    + "사용자당 하나만 유지되어 새로 지정하면 기존 대상은 해제된다. "
                    + "Discord가 아닌 웹훅은 지정할 수 없다.")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<WebhookCredentialResponse>> updateAlertTarget(
            CustomUserDetails userDetails,
            @Parameter(description = "웹훅 자격증명 ID") UUID id,
            UpdateAlertTargetRequest request);

    @Operation(summary = "웹훅 자격증명 삭제")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<Void>> delete(CustomUserDetails userDetails,
                                             @Parameter(description = "웹훅 자격증명 ID") UUID id);
}
