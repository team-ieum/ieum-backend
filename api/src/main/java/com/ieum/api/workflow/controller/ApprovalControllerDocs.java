package com.ieum.api.workflow.controller;

import com.ieum.api.workflow.dto.ApprovalGateDetailResponse;
import com.ieum.api.workflow.dto.ApprovalGateSummaryResponse;
import com.ieum.api.workflow.dto.ApproveGateRequest;
import com.ieum.api.workflow.dto.RejectGateRequest;
import com.ieum.api.workflow.dto.SubmitGateRequest;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import com.ieum.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag(name = "승인 게이트", description = "승인 요청·값 입력받기 노드의 대기 목록·상세·처리")
@SecurityRequirement(name = "BearerAuth")
public interface ApprovalControllerDocs {

    @Operation(summary = "내 승인 대기 목록",
        description = "결정 안 됐고 실행이 WAITING_APPROVAL인 내 게이트, 최신순. title은 렌더된 message 앞 200자. "
            + "cursor는 페이지 번호(빈 값 = 첫 페이지, 숫자가 아니면 400 INVALID_CURSOR), size 기본 20·최대 50.")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<PageResponse<ApprovalGateSummaryResponse>>> list(
            CustomUserDetails userDetails,
            @Parameter(description = "커서 (페이지 번호)") String cursor,
            @Parameter(description = "페이지 크기 (최대 50)") int size);

    @Operation(summary = "승인 게이트 상세",
        description = "view는 화면용 config(message·items·fields·라벨 등, 렌더·마스킹됨). 결정된 게이트도 본다"
            + "(decision·decidedAt). 본인 게이트만 — 아니면 404 APPROVAL_GATE_NOT_FOUND.")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<ApprovalGateDetailResponse>> detail(
            CustomUserDetails userDetails,
            @Parameter(description = "게이트 ID") UUID gateId);

    @Operation(summary = "승인 요청 승인",
        description = "approval.request 게이트를 승인한다. content는 editable 게이트에서만(items key → 문자열). "
            + "남은 게이트가 있으면 원 실행(WAITING_APPROVAL, pendingApprovalNodeIds = 남은 게이트), 마지막이면 이어진 "
            + "실행을 돌려준다. 이미 처리됨·동작 불일치 409, 입력 오류 400, 남의 게이트 404.")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<WorkflowExecutionResponse>> approve(
            CustomUserDetails userDetails,
            @Parameter(description = "게이트 ID") UUID gateId,
            @Valid ApproveGateRequest request);

    @Operation(summary = "승인 요청 거절",
        description = "approval.request 게이트를 거절한다. onReject=stop이면 실행 FAILED(\"승인 거부[: note]\", 알림 없음), "
            + "continue면 decision rejected로 이어간다. note는 선택·200자 이하.")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<WorkflowExecutionResponse>> reject(
            CustomUserDetails userDetails,
            @Parameter(description = "게이트 ID") UUID gateId,
            @Valid RejectGateRequest request);

    @Operation(summary = "값 입력받기 제출",
        description = "approval.collect 게이트에 값을 제출한다. values는 필수(칸 key → 문자열·숫자·불리언), 칸 검사 실패는 "
            + "400 APPROVAL_INPUT_INVALID. 응답 규칙은 승인과 같다.")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<WorkflowExecutionResponse>> submit(
            CustomUserDetails userDetails,
            @Parameter(description = "게이트 ID") UUID gateId,
            @Valid SubmitGateRequest request);
}
