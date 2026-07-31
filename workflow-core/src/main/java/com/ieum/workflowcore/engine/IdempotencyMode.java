package com.ieum.workflowcore.engine;

/**
 * 재시도 시 중복 외부 호출을 어떻게 막을지 결정하는 모드.
 *
 * <p>노드 config의 {@code retry.idempotency}로 선언한다.
 */
public enum IdempotencyMode {

    /** 아무것도 하지 않는다 */
    NONE,
    /** 외부 요청에 {@code Idempotency-Key} 헤더를 주입한다(상대가 지원해야 실효) */
    HEADER,
    /**
     * 외부 호출 직전 Redis에 in-flight 마커를 남기고, 재시도 시 마커가 있으면
     * 이전 호출이 도달했을 수 있다고 보아 재시도를 포기한다.
     *
     * <p>중복은 확실히 막지만 timeout 재시도가 무력화된다 — 기본값이 아닌 이유다.
     */
    MARKER,
    /** {@link #HEADER} + {@link #MARKER} */
    BOTH;

    public boolean usesHeader() {
        return this == HEADER || this == BOTH;
    }

    public boolean usesMarker() {
        return this == MARKER || this == BOTH;
    }
}
