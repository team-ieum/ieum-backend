package com.ieum.api.config;

import com.ieum.workflowcore.engine.executor.IdempotencyStore;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * IdempotencyStore 실제 구현체 (어댑터). Redis SETNX(setIfAbsent)로 in-flight 마커를 관리한다.
 *
 * <p>{@code @Primary}로 스캔 순서와 무관하게 항상 이 빈이 선택되도록 고정한다
 * (다른 Provider 포트와 동일한 이유 — {@code DefaultBetaPlatformProvider} 참고).
 *
 * <p>Redis 장애가 워크플로우 실행을 막으면 안 된다 — 중복 호출 위험을 감수하고 가용성을
 * 택한 의도적 트레이드오프다. 그래서 예외는 잡아서 {@link #markInFlight}는 true(=마커 없었음,
 * 진행해도 됨)를 반환하고, {@link #clearInFlight}는 무시하고 warn 로그만 남긴다. 이 동작을
 * 되돌려 예외를 전파하면 Redis 장애 시 모든 재시도 가능 노드가 실행 불가 상태가 된다.
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class DefaultIdempotencyStore implements IdempotencyStore {

    private static final String KEY_PREFIX = "idem:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean markInFlight(String key, Duration ttl) {
        try {
            Boolean marked = redisTemplate.opsForValue().setIfAbsent(prefixed(key), "1", ttl);
            return !Boolean.FALSE.equals(marked);
        } catch (Exception e) {
            log.warn("[DefaultIdempotencyStore] markInFlight 중 Redis 장애 — key: {}, 진행 허용", key, e);
            return true;
        }
    }

    @Override
    public void clearInFlight(String key) {
        try {
            redisTemplate.delete(prefixed(key));
        } catch (Exception e) {
            log.warn("[DefaultIdempotencyStore] clearInFlight 중 Redis 장애 — key: {}, 무시", key, e);
        }
    }

    private String prefixed(String key) {
        return KEY_PREFIX + key;
    }
}
