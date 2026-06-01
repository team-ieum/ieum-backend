package com.ieum.api.workflow.controller;

import com.ieum.api.workflow.dto.WorkflowDashboardErrorResponse;
import com.ieum.api.workflow.dto.WorkflowDashboardExecutionResponse;
import com.ieum.api.workflow.dto.WorkflowDashboardSummaryResponse;
import com.ieum.api.workflow.service.WorkflowDashboardService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import com.ieum.common.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workflows/dashboard")
@RequiredArgsConstructor
public class WorkflowDashboardController implements WorkflowDashboardControllerDocs {

    private final WorkflowDashboardService workflowDashboardService;

    @Override
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<WorkflowDashboardSummaryResponse>> getSummary(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        WorkflowDashboardSummaryResponse response = workflowDashboardService.getDashboardSummary(userDetails.getId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Override
    @GetMapping("/executions")
    public ResponseEntity<ApiResponse<PageResponse<WorkflowDashboardExecutionResponse>>> getRecentExecutions(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<WorkflowDashboardExecutionResponse> response =
            workflowDashboardService.getRecentExecutions(userDetails.getId(), cursor, size);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Override
    @GetMapping("/errors")
    public ResponseEntity<ApiResponse<PageResponse<WorkflowDashboardErrorResponse>>> getRecentErrors(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<WorkflowDashboardErrorResponse> response =
            workflowDashboardService.getRecentErrors(userDetails.getId(), cursor, size);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
