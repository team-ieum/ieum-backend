package com.ieum.api.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 워크플로우당 분당 60회(IEUM-BE-91 Task 4). Redis INCR 스크립트는 메모리 맵으로 흉내 낸다. */
class WebhookRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final Map<String, Long> counts = new HashMap<>();
    private final WebhookRateLimiter limiter = new WebhookRateLimiter(redis);

    private final UUID workflowId = UUID.randomUUID();
    private final Instant minute = Instant.parse("2026-10-12T10:00:30Z");

    WebhookRateLimiterTest() {
        given(redis.execute(eq(WebhookRateLimiter.INCREMENT), anyList(), any()))
            .willAnswer(inv -> counts.merge(inv.<List<String>>getArgument(1).get(0), 1L, Long::sum));
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
    @DisplayName("60번째까지 통과, 61번째는 막힌다 — 키는 webhook:rate:{id}:{epochMinute}, TTL 120초는 스크립트 인자")
    void sixtyFirstIsLimited() {
        assertThat(passes(workflowId, minute, 60)).isEqualTo(60);
        assertThat(limiter.tryAcquire(workflowId, minute)).isFalse();

        String key = "webhook:rate:" + workflowId + ":" + minute.getEpochSecond() / 60;
        assertThat(counts).containsOnlyKeys(key);
        verify(redis, atLeastOnce()).execute(WebhookRateLimiter.INCREMENT, List.of(key), "120");
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
        given(redis.execute(eq(WebhookRateLimiter.INCREMENT), anyList(), any())).willThrow(new RedisConnectionFailureException("down"));
        assertThat(limiter.tryAcquire(workflowId)).isTrue();
    }
}
