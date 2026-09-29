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
     * <p>같은 실행 안의 재시도는 막지만 timeout 재시도가 무력화된다 — 기본값이 아닌 이유다.
     *
     * <p>프로세스 크래시 후 잡 큐 회수 재배달(at-least-once)은 막지 못할 수 있다. 마커 TTL(기본 5분)과
     * 큐 {@code reclaim-min-idle}(기본 10분) 타이밍에 달려 있어, 재배달이 그 노드에 닿을 때 마커가 이미
     * 만료됐으면 외부 호출이 중복되고 아직 살아 있으면 그 노드가 중복 호출 차단으로 실패한다.
     * 재처리 API는 새 실행 키라 대상이 아니다.
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
