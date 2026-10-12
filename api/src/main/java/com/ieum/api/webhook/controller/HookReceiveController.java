package com.ieum.api.webhook.controller;

import com.ieum.api.webhook.dto.HookReceiveResponse;
import com.ieum.api.webhook.service.HookPayloadReader;
import com.ieum.api.webhook.service.WebhookService;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hooks")
@RequiredArgsConstructor
public class HookReceiveController implements HookReceiveControllerDocs {

    private final HookPayloadReader hookPayloadReader;
    private final WebhookService webhookService;

    @RequestMapping(value = "/{token}", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT})
    public ResponseEntity<HookReceiveResponse> receive(@PathVariable String token, HttpServletRequest request) {
        WorkflowExecutionResponse execution = webhookService.receive(token, hookPayloadReader.read(request));
        return ResponseEntity.ok(new HookReceiveResponse(true, execution == null ? null : execution.getId()));
    }
}
