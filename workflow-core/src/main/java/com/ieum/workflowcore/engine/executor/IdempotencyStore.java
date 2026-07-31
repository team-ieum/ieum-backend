package com.ieum.workflowcore.engine.executor;

import java.time.Duration;

/**
 * 재시도 시 중복 외부 호출을 막기 위한 in-flight 마커 저장소 포트 인터페이스.
 * api 모듈의 어댑터(DefaultIdempotencyStore)가 Redis(StringRedisTemplate)로 구현하여 주입된다.
 *
 * <p>workflow-core는 api 모듈(Redis)에 직접 의존하지 않으므로, 의존성 역전을 위해
 * 이 포트로 마커를 세우고/해제한다. {@link com.ieum.workflowcore.engine.IdempotencyMode#MARKER}가
 * 선언된 노드에서만 사용된다.
 */
public interface IdempotencyStore {

    /**
     * 마커를 새로 세운다(SETNX 의미).
     *
     * @param key 마커 키 ({@link com.ieum.workflowcore.engine.IdempotencyKeys}로 생성)
     * @param ttl 마커 유효 시간
     * @return 마커를 새로 세웠으면 true, 이미 있었으면 false. 구현체는 저장소 장애 시
     *     안전하게 true(= 진행 허용)로 폴백할 수 있다.
     */
    boolean markInFlight(String key, Duration ttl);

    /**
     * 호출이 종료(성공/실패 확정)되었음을 기록해 마커를 해제한다.
     *
     * <p>재시도 루프 전체가 끝난 뒤(마지막 attempt까지 확정된 뒤) 한 번만 호출해야 한다.
     * 개별 attempt의 finally에서 호출하면 다음 attempt가 markInFlight로 마커를 다시 세울 수 있어
     * {@link com.ieum.workflowcore.engine.IdempotencyMode#MARKER}가 무력화된다.
     */
    void clearInFlight(String key);
}
