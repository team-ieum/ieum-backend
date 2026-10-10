package com.ieum.api.workflow.controller;

import com.ieum.api.workflow.dto.NodeTestRequest;
import com.ieum.api.workflow.dto.NodeTestResponse;
import com.ieum.api.workflow.service.NodeTestService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
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
import org.springframework.web.bind.annotation.RestController;

/** {@code WorkflowController}와 분리한 이유: 그 컨트롤러 생성자를 여러 테스트가 직접 호출해 의존을 늘리면 깨진다. */
@RestController
@RequestMapping("/api/v1/workflows/{workflowId}/nodes/{nodeId}")
@RequiredArgsConstructor
public class NodeTestController implements NodeTestControllerDocs {

    private final NodeTestService nodeTestService;

    @Override
    @PostMapping("/test")
    public ResponseEntity<ApiResponse<NodeTestResponse>> test(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID workflowId,
            @PathVariable String nodeId,
            @RequestBody(required = false) @Valid NodeTestRequest request) {

        NodeTestRequest body = request != null ? request : new NodeTestRequest();
        return ResponseEntity.ok(ApiResponse.ok(
            nodeTestService.test(userDetails.getId(), workflowId, nodeId, body)));
    }

    @Override
    @GetMapping("/sample")
    public ResponseEntity<ApiResponse<NodeTestResponse>> getSample(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID workflowId,
            @PathVariable String nodeId) {

        return ResponseEntity.ok(ApiResponse.ok(
            nodeTestService.getSample(userDetails.getId(), workflowId, nodeId)));
    }
}
