package com.ieum.api.webhook.dto;

import java.util.UUID;

/**
 * 토큰 주소 웹훅 응답 — 외부 호출자용이라 {@code ApiResponse} 래퍼 없이 평문이다(IEUM-BE-91 R7).
 * 노드 테스트 샘플만 저장하고 실행하지 않았으면 {@code executionId}는 null.
 */
public record HookReceiveResponse(boolean ok, UUID executionId) {
}
