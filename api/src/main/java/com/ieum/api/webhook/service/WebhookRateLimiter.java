package com.ieum.api.webhook.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 토큰 주소 웹훅의 워크플로우당 분당 수신 한도(IEUM-BE-91 Task 4). 고정 창 — 키
 * {@code webhook:rate:{workflowId}:{epochMinute}}를 INCR하고 첫 증가면 TTL 120초.
 *
 * <p><b>Redis 장애 시에는 통과시킨다</b> — 웹훅 유실보다 한도 미적용이 낫다는 판단으로,
 * {@code AlertCooldownStore}·{@code DefaultIdempotencyStore}와 같은 방향이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookRateLimiter {

    static final int LIMIT_PER_MINUTE = 60;
    private static final Duration TTL = Duration.ofSeconds(120);

    private final StringRedisTemplate redisTemplate;

    /** @return 이번 분 한도 안이면 true(이번 요청을 센다), 넘었으면 false */
    public boolean tryAcquire(UUID workflowId) {
        return tryAcquire(workflowId, Instant.now());
    }

    boolean tryAcquire(UUID workflowId, Instant now) {
        String key = "webhook:rate:" + workflowId + ":" + now.getEpochSecond() / 60;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, TTL);
            }
            return count == null || count <= LIMIT_PER_MINUTE;
        } catch (Exception e) {
            log.warn("[WebhookRateLimiter] Redis 장애 — 한도 없이 통과. workflowId: {}", workflowId, e);
            return true;
        }
    }
}
