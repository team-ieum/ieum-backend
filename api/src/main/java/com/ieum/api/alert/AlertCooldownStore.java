package com.ieum.api.alert;

import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 워크플로우 단위 알림 쿨다운. 반복 실패하는 워크플로우가 채널을 도배하는 것을 막는다.
 *
 * <p>Redis SETNX로 구현한다 — 첫 실패만 마커를 세우고 TTL 동안 뒤따르는 실패는 막힌다.
 * 워크플로우별이라 다른 워크플로우의 실패는 계속 알림이 간다.
 *
 * <p><b>Redis 장애 시에는 발신을 허용한다</b> — 알림을 잃는 것보다 도배 위험이 낫다는
 * 의도적 트레이드오프다({@code DefaultIdempotencyStore}와 같은 방향).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertCooldownStore {

    private static final String KEY_PREFIX = "alert:cooldown:";
    private static final Duration COOLDOWN = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    /**
     * 이 워크플로우에 대해 지금 발신해도 되는지 판단하고, 발신 가능하면 쿨다운을 시작한다.
     *
     * @return 발신해도 되면 true, 쿨다운 중이면 false
     */
    public boolean tryAcquire(UUID workflowId) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(KEY_PREFIX + workflowId, "1", COOLDOWN);
            return !Boolean.FALSE.equals(acquired);
        } catch (Exception e) {
            log.warn("[AlertCooldownStore] Redis 장애 — 쿨다운 없이 발신 허용. workflowId: {}", workflowId, e);
            return true;
        }
    }
}
