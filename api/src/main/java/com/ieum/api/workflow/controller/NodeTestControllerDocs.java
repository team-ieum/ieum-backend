package com.ieum.api.workflow.controller;

import com.ieum.api.workflow.dto.NodeTestRequest;
import com.ieum.api.workflow.dto.NodeTestResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag(name = "노드 테스트", description = "노드 하나를 단독 실행하고 결과 샘플을 남긴다 — 샘플이 다음 노드의 참조식·매핑 근거가 된다")
@SecurityRequirement(name = "BearerAuth")
public interface NodeTestControllerDocs {

    @Operation(
        summary = "노드 테스트 실행",
        description = "노드 하나를 저장·활성 여부와 무관하게 실제로 실행하고 결과를 최신 샘플 1건으로 저장한다. "
            + "**쓰기 액션(이슈 생성·메일 발송 등)은 실제로 실행된다.** AI 노드는 쿼터·토큰이 정상 차감된다. "
            + "body.node가 없으면 저장된 최신 버전의 노드를 쓰고, 있으면 그 편집 중 정의를 쓴다(node.id는 경로 nodeId와 같아야 한다). "
            + "이 노드가 `{{nodes.<id>.output.…}}`로 직접 참조한 앞 노드는 SUCCESS 샘플이 있어야 한다 — 없으면 "
            + "400 TEST_SAMPLE_MISSING(data.missingNodeIds). 실행 실패는 HTTP 200 + status FAILED이며 FAILED 샘플이 "
            + "이전 SUCCESS 샘플을 덮어쓴다. output의 민감 키(token·apiKey 등)는 마스킹된다. "
            + "manual 트리거는 input을 페이로드로 쓴다(샘플은 치환된 트리거 config — 페이로드 필드는 config가 "
            + "`{{nodes.<triggerId>.output.<key>}}`로 자기 참조할 때만 남는다). "
            + "webhook 트리거는 실행하지 않고 LISTENING(webhookUrl 경로 /api/v1/webhooks/{workflowId}, expiresAt)을 돌려준다 — 5분 안에 그 경로로 "
            + "온 첫 요청 1건이 샘플이 되며, FE는 GET .../sample을 폴링해 testedAt이 시작 시각 이후인지 본다. "
            + "수신 시 트리거 정의는 저장된 최신 버전에서 읽으므로 webhook 테스트 전에 저장해야 한다. "
            + "approval 게이트는 실행 없이 런타임 승인 출력 모양({approved: true, approvedBy: 소유자 ID, approvedAt})을 "
            + "SUCCESS 샘플로 남긴다 — 하류의 `{{nodes.<gate>.output.approvedBy}}` 참조를 테스트하기 위해서다. "
            + "오류 code: WORKFLOW_NOT_FOUND(404, 남의 워크플로우 포함), NOT_FOUND(404, 저장된 노드 없음), "
            + "INVALID_INPUT(400, node.id 불일치·실행기가 없는 타입), INVALID_WORKFLOW(400, 남의 크레덴셜·웹훅 URL 원문·"
            + "API 키 원문), TEST_SAMPLE_MISSING(400)")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<NodeTestResponse>> test(
        CustomUserDetails userDetails,
        @Parameter(description = "워크플로우 ID") UUID workflowId,
        @Parameter(description = "테스트할 노드 ID") String nodeId,
        @Valid NodeTestRequest request);

    @Operation(
        summary = "노드 최신 샘플 조회",
        description = "노드의 최신 테스트 샘플(SUCCESS 또는 FAILED)을 돌려준다. 없으면 404 TEST_SAMPLE_NOT_FOUND. "
            + "webhook 대기 중 FE가 폴링하는 엔드포인트이기도 하다(TTL이 지나도 샘플이 없으면 '수신 없음').")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<NodeTestResponse>> getSample(
        CustomUserDetails userDetails,
        @Parameter(description = "워크플로우 ID") UUID workflowId,
        @Parameter(description = "노드 ID") String nodeId);
}
