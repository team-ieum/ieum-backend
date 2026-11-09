package com.ieum.api.workflow.controller;

import com.ieum.api.workflow.dto.ApprovalGateDetailResponse;
import com.ieum.api.workflow.dto.ApprovalGateSummaryResponse;
import com.ieum.api.workflow.dto.ApproveGateRequest;
import com.ieum.api.workflow.dto.RejectGateRequest;
import com.ieum.api.workflow.dto.SubmitGateRequest;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.api.workflow.service.ApprovalGateService;
import com.ieum.api.workflow.service.ApprovalQueryService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import com.ieum.common.dto.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 승인 게이트 API. {@code WorkflowController} 생성자를 여러 테스트가 직접 호출해 따로 둔다. */
@RestController
@RequestMapping("/api/v1/approvals")
@RequiredArgsConstructor
public class ApprovalController implements ApprovalControllerDocs {

    private final ApprovalQueryService approvalQueryService;
    private final ApprovalGateService approvalGateService;

    @Override
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ApprovalGateSummaryResponse>>> list(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(approvalQueryService.list(userDetails.getId(), cursor, size)));
    }

    @Override
    @GetMapping("/{gateId}")
    public ResponseEntity<ApiResponse<ApprovalGateDetailResponse>> detail(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID gateId) {
        return ResponseEntity.ok(ApiResponse.ok(approvalQueryService.detail(userDetails.getId(), gateId)));
    }

    @Override
    @PostMapping("/{gateId}/approve")
    public ResponseEntity<ApiResponse<WorkflowExecutionResponse>> approve(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID gateId,
            @RequestBody @Valid ApproveGateRequest request) {
        WorkflowExecutionResponse response = approvalGateService.approve(
            userDetails.getId(), gateId, request.getNote(), request.getContent());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Override
    @PostMapping("/{gateId}/reject")
    public ResponseEntity<ApiResponse<WorkflowExecutionResponse>> reject(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID gateId,
            @RequestBody(required = false) @Valid RejectGateRequest request) {
        String note = request != null ? request.getNote() : null;
        WorkflowExecutionResponse response = approvalGateService.reject(userDetails.getId(), gateId, note);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Override
    @PostMapping("/{gateId}/submit")
    public ResponseEntity<ApiResponse<WorkflowExecutionResponse>> submit(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID gateId,
            @RequestBody @Valid SubmitGateRequest request) {
        WorkflowExecutionResponse response = approvalGateService.submit(
            userDetails.getId(), gateId, request.getValues(), request.getNote());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
