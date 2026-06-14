package com.ieum.api.integration.controller;

import com.ieum.api.integration.dto.WorkflowSummaryResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import com.ieum.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag(name = "연동 서비스", description = "연결된 서비스별 워크플로우 조회 (통합설정 > 연결된 서비스 관리)")
@SecurityRequirement(name = "BearerAuth")
public interface IntegrationWorkflowControllerDocs {

    @Operation(
        summary = "연동 서비스별 워크플로우 목록 조회",
        description = "특정 연동 서비스를 사용하는 워크플로우 목록을 조회합니다. 각 워크플로우의 최신 버전 기준이며, "
            + "해당 서비스를 사용하는 노드 수(usedNodeCount)를 함께 반환합니다. "
            + "지원 serviceType: GOOGLE, NOTION, GITHUB, SLACK, DISCORD (대소문자 무관)")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<PageResponse<WorkflowSummaryResponse>>> getWorkflowsByService(
        CustomUserDetails userDetails,
        @Parameter(description = "연동 서비스 타입", example = "DISCORD") String serviceType,
        @Parameter(description = "페이지 커서 (다음 페이지 nextCursor 값)") String cursor,
        @Parameter(description = "페이지 크기", example = "20") int size);
}
