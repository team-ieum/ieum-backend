package com.ieum.api.webhook.controller;

import com.ieum.api.webhook.dto.WebhookTriggerRequest;
import com.ieum.api.webhook.service.WebhookService;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.common.dto.ApiResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks")
@RequiredArgsConstructor
public class WebhookController implements WebhookControllerDocs {

    private final WebhookService webhookService;

    @PostMapping("/{workflowId}")
    public ResponseEntity<ApiResponse<WorkflowExecutionResponse>> trigger(
            @PathVariable UUID workflowId,
            @RequestBody(required = false) WebhookTriggerRequest request) {

        WorkflowExecutionResponse response = webhookService.trigger(workflowId, request);
        return ResponseEntity.status(202).body(ApiResponse.ok(response));
    }
}
