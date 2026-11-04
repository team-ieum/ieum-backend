package com.ieum.workflowcore.engine.executor;

import java.util.UUID;

/**
 * AI 호출 1회의 토큰 사용량을 기록하는 포트(IEUM-BE-98).
 * api 모듈의 {@code DefaultAiUsageRecorder}가 {@code ai_token_usage} 테이블에 저장한다 —
 * provider enum·모델 카탈로그가 api에 있어 저장 로직도 거기 둔다.
 *
 * <p><b>구현체는 절대 예외를 던지지 않아야 한다</b> — 사용량 기록 실패가 노드 실행을 깨면 안 된다.
 * 호출부도 try-catch로 한 번 더 감싼다.
 */
public interface AiUsageRecorder {

    void record(AiUsageCall call);

    /**
     * @param provider       노드 설정의 llmProvider. platform 키면 구현체가 GEMINI로 본다
     * @param requestedModel 이번 회차에 요청한 모델(재시도 fallback 반영, nullable)
     * @param reportedModel  agent 응답에 실린 실제 모델(nullable — agent가 아직 싣지 않는다)
     * @param executionId    워크플로우 실행 ID. 노드 단일 테스트면 null
     */
    record AiUsageCall(
        UUID userId, String provider, String requestedModel, String reportedModel, boolean platformKey,
        UUID executionId, Integer promptTokens, Integer completionTokens, Integer totalTokens
    ) {}
}
