package com.ieum.api.trigger.github;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/github")
@RequiredArgsConstructor
public class GitHubWebhookController implements GitHubWebhookControllerDocs {

    private final GitHubWebhookService gitHubWebhookService;

    /** 서명은 raw 바이트 기준이라 본문을 역직렬화하지 않고 byte[]로 받는다. */
    @PostMapping("/webhook")
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-GitHub-Hook-Installation-Target-ID", required = false) String hookTargetId,
            @RequestBody(required = false) byte[] body) {
        gitHubWebhookService.receive(signature, event, deliveryId, hookTargetId, body);
        return ResponseEntity.accepted().build();
    }
}
