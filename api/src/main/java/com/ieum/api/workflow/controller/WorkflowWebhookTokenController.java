package com.ieum.api.workflow.controller;

import com.ieum.api.workflow.dto.WebhookTokenResponse;
import com.ieum.api.workflow.dto.WebhookTokenStatusResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowWebhookTokenService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code WorkflowController}와 분리한 이유: 그 컨트롤러 생성자를 여러 테스트가 직접 호출해 의존을 늘리면 깨진다. */
@RestController
@RequestMapping("/api/v1/workflows/{workflowId}/webhook-token")
@RequiredArgsConstructor
public class WorkflowWebhookTokenController implements WorkflowWebhookTokenControllerDocs {

    private final WorkflowCrudService workflowCrudService;
    private final WorkflowWebhookTokenService webhookTokenService;

    @Override
    @PostMapping
    public ResponseEntity<ApiResponse<WebhookTokenResponse>> issue(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID workflowId) {

        Workflow workflow = workflowCrudService.getWorkflowByOwner(userDetails.getId(), workflowId);
        return ResponseEntity.ok(ApiResponse.ok(WebhookTokenResponse.from(webhookTokenService.issue(workflow))));
    }

    @Override
    @GetMapping
    public ResponseEntity<ApiResponse<WebhookTokenStatusResponse>> status(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID workflowId) {

        workflowCrudService.getWorkflowByOwner(userDetails.getId(), workflowId);
        return ResponseEntity.ok(ApiResponse.ok(
            WebhookTokenStatusResponse.from(webhookTokenService.status(workflowId))));
    }
}
