package com.ieum.api.webhook.controller;

import com.ieum.api.webhook.dto.HookReceiveResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

@Tag(name = "Webhook", description = "외부 시스템에서 워크플로우를 트리거하는 Webhook API (인증 불필요)")
public interface HookReceiveControllerDocs {

    @Operation(
        summary = "Webhook 원문 수신 (토큰 주소)",
        description = """
            외부 서비스가 보내는 웹훅 본문을 감싸지 않은 원문 그대로 받아 워크플로우를 실행한다. GET·POST·PUT.

            - 인증(JWT) 불필요 — URL의 토큰이 비밀이다. 토큰은 `POST /api/v1/workflows/{workflowId}/webhook-token`으로 발급·재발급
            - 토큰이 없거나 맞지 않으면 404
            - 워크플로우당 분당 60회, 넘으면 429 `WEBHOOK_RATE_LIMITED`(본문을 읽기 전에 센다)
            - 본문 최대 5MB, 넘으면 413 `WEBHOOK_PAYLOAD_TOO_LARGE`
            - 트리거 출력: JSON 객체는 그대로, 배열은 `{"items": [...]}`, 스칼라는 `{"value": x}`,
              form(`application/x-www-form-urlencoded`)은 키→값(같은 키가 여러 번이면 리스트), 그 밖의 Content-Type은 `{"value": "원문"}`.
              Content-Type이 없으면 JSON으로 읽는다. 깨진 JSON은 400
            - 쿼리 파라미터는 출력에 합쳐지며 본문 키가 우선한다. GET이거나 본문이 없으면 쿼리만
            - 트리거 노드 `config.includeRaw`가 true면 출력이 `{method, headers, query, body, rawBody}`다 — `body`는 위 출력,
              `rawBody`는 원문 문자열(UTF-8), `headers`는 소문자 키이고 자격증명 헤더(`authorization`·`proxy-authorization`·`cookie`·`set-cookie`·`x-api-key`·`x-auth-token`·`x-access-token`·`x-csrf-token`·`x-xsrf-token`)는 `"***"`로 가린다. 서명 헤더(`x-hub-signature-256` 등)는 검증용이라 그대로 둔다
            - 워크플로우가 `WEBHOOK` 트리거 타입이고 활성 상태여야 실행된다(아니면 400). 노드 테스트가 수신을 기다리는 중이면 첫 1건은 샘플로 저장된다
            - **응답은 `ApiResponse` 래퍼가 아니다** — 외부 호출자용 계약 예외로 200 `{"ok": true, "executionId": "<uuid>"}`.
              샘플만 저장하고 실행하지 않았으면 `executionId`는 null. 오류 응답은 다른 API와 같은 형식
            """
    )
    ResponseEntity<HookReceiveResponse> receive(
        @Parameter(description = "웹훅 토큰(발급 응답의 path에 담긴 값)", required = true) String token,
        @Parameter(hidden = true) HttpServletRequest request
    );
}
