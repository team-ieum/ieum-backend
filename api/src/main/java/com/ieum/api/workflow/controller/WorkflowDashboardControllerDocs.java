package com.ieum.api.workflow.controller;

import com.ieum.api.workflow.dto.WorkflowDashboardErrorResponse;
import com.ieum.api.workflow.dto.WorkflowDashboardExecutionResponse;
import com.ieum.api.workflow.dto.WorkflowDashboardSummaryResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import com.ieum.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag(name = "워크플로우 대시보드", description = "대시보드 통계 및 실행/오류 이력 조회")
@SecurityRequirement(name = "BearerAuth")
public interface WorkflowDashboardControllerDocs {

    @Operation(summary = "대시보드 종합 요약 통계 조회", description = "오늘 실행 수, 어제 대비 비율, 성공률, 평균 시간, 24시간 차트 및 활성/비활성/오류/실행중 카드 통계 조회")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<WorkflowDashboardSummaryResponse>> getSummary(
            CustomUserDetails userDetails);

    @Operation(summary = "최근 실행 이력 목록 조회", description = "사용자가 소유한 모든 워크플로우의 실행 이력을 페이징하여 조회")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<PageResponse<WorkflowDashboardExecutionResponse>>> getRecentExecutions(
            CustomUserDetails userDetails,
            @Parameter(description = "커서 (페이지 번호)") String cursor,
            @Parameter(description = "페이지 크기") int size);

    @Operation(summary = "최근 에러 발생 목록 조회", description = "실패한 워크플로우 실행 목록과 실패 원인이 된 노드의 에러 메시지를 페이징하여 조회")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<PageResponse<WorkflowDashboardErrorResponse>>> getRecentErrors(
            CustomUserDetails userDetails,
            @Parameter(description = "커서 (페이지 번호)") String cursor,
            @Parameter(description = "페이지 크기") int size);
}
