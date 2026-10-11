package com.ieum.api.trigger.github;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "GitHub Webhook", description = "GitHub App webhook 수신 (JWT 불필요, HMAC 서명 인증)")
public interface GitHubWebhookControllerDocs {

    @Operation(
        summary = "GitHub App webhook 수신",
        description = """
            GitHub App이 호출한다. 사용자가 직접 부르지 않는다.

            - `X-Hub-Signature-256` = HMAC-SHA256(github.app.webhook-secret, raw body). 불일치·누락 → 401
            - `X-GitHub-Event: issues` + `action: opened`만 처리, 그 외(ping 포함) → 202
            - IEUM GitHub 액션(이 App)이 만든 이슈는 실행하지 않는다(202) — 자기 발화 루프 방지
            - `X-GitHub-Delivery` 4일 멱등(GitHub 재전송 창 3일) — 같은 delivery 재전송은 실행을 만들지 않는다(202)
            - 202 후 비동기로 `GITHUB_NEW_ISSUE` 구독(repository.id)을 찾아 소유자 레포 접근을 재확인하고 실행한다
            """
    )
    ResponseEntity<Void> receive(String signature, String event, String deliveryId, byte[] body);
}
