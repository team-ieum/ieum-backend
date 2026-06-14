package com.ieum.api.integration.controller;

import com.ieum.api.integration.domain.IntegrationServiceType;
import com.ieum.api.integration.dto.WorkflowSummaryResponse;
import com.ieum.api.integration.service.IntegrationWorkflowService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import com.ieum.common.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations")
@RequiredArgsConstructor
public class IntegrationWorkflowController implements IntegrationWorkflowControllerDocs {

    private final IntegrationWorkflowService integrationWorkflowService;

    @Override
    @GetMapping("/{serviceType}/workflows")
    public ResponseEntity<ApiResponse<PageResponse<WorkflowSummaryResponse>>> getWorkflowsByService(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable String serviceType,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {

        PageResponse<WorkflowSummaryResponse> response =
            integrationWorkflowService.getWorkflowsByService(
                userDetails.getId(), IntegrationServiceType.from(serviceType), cursor, size);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
