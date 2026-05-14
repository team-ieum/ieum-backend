package com.ieum.api.webhook.controller;

import com.ieum.api.webhook.dto.WebhookTriggerRequest;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

@Tag(name = "Webhook", description = "외부 시스템에서 워크플로우를 트리거하는 Webhook API (인증 불필요)")
public interface WebhookControllerDocs {

    @Operation(
        summary = "Webhook 트리거",
        description = """
            외부 시스템이 특정 워크플로우를 트리거한다.

            - 인증(JWT) 불필요 — URL의 workflowId가 식별자 역할
            - 워크플로우가 `WEBHOOK` 트리거 타입이고 활성 상태여야 실행됨
            - payload는 선택 사항이며, 노드에서 변수로 참조 가능
            - 실행은 비동기로 처리되며 응답은 즉시 반환됨 (202 Accepted)
            """
    )
    @RequestBody(content = @Content(
        mediaType = "application/json",
        examples = {
            @ExampleObject(
                name = "주문 이벤트",
                summary = "쇼핑몰 주문 발생 시 — WEBHOOK 주문 알림 워크플로우용",
                value = """
                    {
                      "payload": {
                        "orderId": "ORD-20260514-001",
                        "amount": 75000,
                        "customerName": "홍길동"
                      }
                    }"""
            ),
            @ExampleObject(
                name = "빈 payload",
                summary = "payload 없이 단순 트리거만",
                value = """
                    {}"""
            )
        }
    ))
    ResponseEntity<ApiResponse<WorkflowExecutionResponse>> trigger(
        @Parameter(description = "트리거할 워크플로우 ID", required = true) UUID workflowId,
        WebhookTriggerRequest request
    );
}
