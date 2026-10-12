package com.ieum.api.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 워크플로우당 분당 60회(IEUM-BE-91 Task 4). Redis INCR은 메모리 맵으로 흉내 낸다. */
class WebhookRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final Map<String, Long> counts = new HashMap<>();
    private final WebhookRateLimiter limiter = new WebhookRateLimiter(redis);

    private final UUID workflowId = UUID.randomUUID();
    private final Instant minute = Instant.parse("2026-10-12T10:00:30Z");

    WebhookRateLimiterTest() {
        given(redis.opsForValue()).willReturn(ops);
        given(ops.increment(anyString())).willAnswer(inv -> counts.merge(inv.getArgument(0), 1L, Long::sum));
    }

    private int passes(UUID id, Instant at, int calls) {
        int passed = 0;
        for (int i = 0; i < calls; i++) {
            if (limiter.tryAcquire(id, at)) {
                passed++;
            }
        }
        return passed;
    }

    @Test
    @DisplayName("60번째까지 통과, 61번째는 막힌다 — 키는 webhook:rate:{id}:{epochMinute}, 첫 증가에만 TTL 120초")
    void sixtyFirstIsLimited() {
        assertThat(passes(workflowId, minute, 60)).isEqualTo(60);
        assertThat(limiter.tryAcquire(workflowId, minute)).isFalse();

        String key = "webhook:rate:" + workflowId + ":" + minute.getEpochSecond() / 60;
        assertThat(counts).containsOnlyKeys(key);
        verify(redis, times(1)).expire(key, Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("다음 분에는 다시 통과한다")
    void nextMinutePassesAgain() {
        passes(workflowId, minute, 61);
        assertThat(limiter.tryAcquire(workflowId, minute.plusSeconds(29))).isFalse(); // 같은 분 10:00:59
        assertThat(limiter.tryAcquire(workflowId, minute.plusSeconds(30))).isTrue();  // 10:01:00
    }

    @Test
    @DisplayName("다른 워크플로우의 카운트와 섞이지 않는다")
    void perWorkflow() {
        passes(workflowId, minute, 61);
        assertThat(passes(UUID.randomUUID(), minute, 60)).isEqualTo(60);
    }

    @Test
    @DisplayName("Redis 장애면 통과시킨다(fail-open)")
    void redisDown_failOpen() {
        given(ops.increment(anyString())).willThrow(new RedisConnectionFailureException("down"));
        assertThat(limiter.tryAcquire(workflowId)).isTrue();
    }
}
