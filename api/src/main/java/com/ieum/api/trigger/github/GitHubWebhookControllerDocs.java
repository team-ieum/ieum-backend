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
            - `X-GitHub-Event: issues` + `action: opened`, `pull_request` + `action` ∈ opened·synchronize·reopened·ready_for_review만
              처리, 그 외(ping 포함) → 202
            - IEUM GitHub 액션(이 App)이 만든 이슈는 실행하지 않는다(202) — 자기 발화 루프 방지.
              판정: `issue.performed_via_github_app.id` == `X-GitHub-Hook-Installation-Target-ID`(이 App의 ID).
              헤더가 없거나 숫자가 아니면 판정 불가 → 통과
            - PR은 `pull_request.author_association` ∈ OWNER·MEMBER·COLLABORATOR(레포 멤버)일 때만 실행한다 — 그 밖은 202
            - `X-GitHub-Delivery` 4일 멱등(GitHub 재전송 창 3일) — 같은 delivery 재전송은 실행을 만들지 않는다(202)
            - 202 후 비동기로 `GITHUB_NEW_ISSUE`·`GITHUB_NEW_PR` 구독(repository.id)을 찾아 소유자 레포 접근을 재확인하고 실행한다
              (PR은 구독마다 이벤트·초안 제외 설정으로 거른다)
            """
    )
    ResponseEntity<Void> receive(String signature, String event, String deliveryId, String hookTargetId, byte[] body);
}
