package com.ieum.api.webhook.controller;

import com.ieum.api.webhook.dto.HookReceiveResponse;
import com.ieum.api.webhook.service.HookPayloadReader;
import com.ieum.api.webhook.service.WebhookService;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
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

    @RequestMapping(value = "/{token}", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
        RequestMethod.HEAD})
    public ResponseEntity<HookReceiveResponse> receive(@PathVariable String token, HttpServletRequest request) {
        if (RequestMethod.HEAD.name().equals(request.getMethod())) {
            // 주소 확인(Trello 등) — 토큰만 보고 실행·listen 소비·횟수 카운트 없이 200
            webhookService.verifyHook(token);
            return ResponseEntity.ok(new HookReceiveResponse(true, null));
        }
        // 토큰 → 횟수 → 본문 순서 — 모르는 토큰·한도 초과 요청엔 본문(최대 5MB)을 읽지 않는다
        UUID workflowId = webhookService.resolveHook(token);
        WorkflowExecutionResponse execution = webhookService.receive(workflowId, hookPayloadReader.read(request));
        return ResponseEntity.ok(new HookReceiveResponse(true, execution == null ? null : execution.getId()));
    }
}
