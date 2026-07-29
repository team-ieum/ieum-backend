package com.ieum.workflowcore.engine;

/**
 * 노드 실행 실패의 원인 분류. 재시도 대상 여부를 결정한다.
 *
 * <p>실패 문자열을 파싱해 추론하지 않는다 — 각 {@code NodeExecutor}가 실패를 반환할 때
 * 명시적으로 채운다. 분류할 근거가 없으면 {@link #UNKNOWN}이다.
 */
public enum FailureKind {

    /** 응답 시간 초과 */
    TIMEOUT(true),
    /** 429 등 호출량 제한 */
    RATE_LIMIT(true),
    /** 5xx 등 상대 서버 오류 */
    SERVER_ERROR(true),
    /** 연결 실패·DNS 오류 등 전송 계층 오류 */
    NETWORK(true),

    /** 4xx 등 요청 자체가 잘못됨 — 같은 요청을 반복해도 결과가 같다 */
    CLIENT_ERROR(false),
    /**
     * 원인 불명. 재시도하지 않는다 — 원인을 모르는 실패를 반복하는 것은
     * 비용만 쓰고 복구 확률이 낮다.
     */
    UNKNOWN(false);

    private final boolean retryable;

    FailureKind(boolean retryable) {
        this.retryable = retryable;
    }

    /** 이 원인이 재시도로 회복될 여지가 있는지 */
    public boolean isRetryable() {
        return retryable;
    }
}
