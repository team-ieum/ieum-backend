package com.ieum.api.webhookcredential.controller;

import com.ieum.api.webhookcredential.dto.CreateWebhookCredentialRequest;
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

    @Operation(summary = "웹훅 자격증명 등록")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<WebhookCredentialResponse>> create(CustomUserDetails userDetails,
                                                                  CreateWebhookCredentialRequest request);

    @Operation(summary = "웹훅 자격증명 목록 조회")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<List<WebhookCredentialResponse>>> getList(CustomUserDetails userDetails);

    @Operation(summary = "웹훅 자격증명 삭제")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<Void>> delete(CustomUserDetails userDetails,
                                             @Parameter(description = "웹훅 자격증명 ID") UUID id);
}
