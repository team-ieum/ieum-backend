package com.ieum.api.workflow.controller;

import com.ieum.api.workflow.dto.WebhookTokenResponse;
import com.ieum.api.workflow.dto.WebhookTokenStatusResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag(name = "웹훅 토큰", description = "외부 서비스가 호출할 웹훅 수신 주소(/api/v1/hooks/{token})의 토큰 발급·조회")
@SecurityRequirement(name = "BearerAuth")
public interface WorkflowWebhookTokenControllerDocs {

    @Operation(
        summary = "웹훅 토큰 발급·재발급",
        description = "워크플로우당 토큰 1개를 발급한다. 이미 있으면 새 토큰으로 바꾸고 이전 토큰은 즉시 무효가 된다. "
            + "평문 token과 수신 경로 path(/api/v1/hooks/{token})는 **이 응답에서만** 볼 수 있다 — 서버는 해시만 저장하므로 "
            + "다시 조회할 수 없고, 잃어버리면 재발급해야 한다. "
            + "오류 code: WORKFLOW_NOT_FOUND(404, 남의 워크플로우 포함)")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<WebhookTokenResponse>> issue(
        CustomUserDetails userDetails,
        @Parameter(description = "워크플로우 ID") UUID workflowId);

    @Operation(
        summary = "웹훅 토큰 발급 여부 조회",
        description = "토큰이 발급됐는지(issued)와 발급 시각(issuedAt, 미발급이면 null)만 돌려준다. 토큰 값은 내보내지 않는다. "
            + "오류 code: WORKFLOW_NOT_FOUND(404, 남의 워크플로우 포함)")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<WebhookTokenStatusResponse>> status(
        CustomUserDetails userDetails,
        @Parameter(description = "워크플로우 ID") UUID workflowId);
}
