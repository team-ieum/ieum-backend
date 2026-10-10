package com.ieum.api.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class WebhookListenStoreTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final WebhookListenStore store = new WebhookListenStore(redis);
    private final UUID workflowId = UUID.randomUUID();
    private final String key = "webhook:listen:" + workflowId;

    @BeforeEach
    void setUp() {
        given(redis.opsForValue()).willReturn(ops);
    }

    @Test
    @DisplayName("start — 워크플로우별 키에 nodeId를 5분 TTL로 세우고 만료 시각을 돌려준다")
    void startSetsKeyWithFiveMinuteTtl() {
        LocalDateTime before = LocalDateTime.now();

        LocalDateTime expiresAt = store.start(workflowId, "node-1");

        verify(ops).set(key, "node-1", Duration.ofMinutes(5));
        assertThat(expiresAt).isBetween(before.plusMinutes(5), LocalDateTime.now().plusMinutes(5));
    }

    @Test
    @DisplayName("start는 Redis 장애를 삼키지 않는다 — 대기 중이 아닌데 LISTENING이라고 하지 않는다")
    void startPropagatesRedisFailure() {
        given(redis.opsForValue()).willThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> store.start(workflowId, "node-1"))
            .isInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    @DisplayName("[Review Focus 2] consume은 GETDEL 한 번이다 — get 후 delete로 나누면 동시 수신이 둘 다 받는다")
    void consumeIsOneAtomicGetAndDelete() {
        given(ops.getAndDelete(key)).willReturn("node-1");

        Optional<String> consumed = store.consume(workflowId);

        assertThat(consumed).contains("node-1");
        verify(ops).getAndDelete(key);
        verifyNoMoreInteractions(ops);
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("[Review Focus 2] 두 번째 소비자는 아무것도 받지 못한다 — 첫 1건만")
    void secondConsumerGetsNothing() {
        given(ops.getAndDelete(key)).willReturn("node-1", (String) null);

        assertThat(store.consume(workflowId)).contains("node-1");
        assertThat(store.consume(workflowId)).isEmpty();
    }

    @Test
    @DisplayName("대기 중이 아니면(키 없음·TTL 경과) empty")
    void consumeWithoutListenerIsEmpty() {
        given(ops.getAndDelete(key)).willReturn(null);

        assertThat(store.consume(workflowId)).isEmpty();
    }

    @Test
    @DisplayName("consume은 Redis 장애를 삼킨다 — 웹훅 실행을 막지 않는다")
    void consumeSwallowsRedisFailure() {
        given(ops.getAndDelete(key)).willThrow(new RedisConnectionFailureException("down"));

        assertThat(store.consume(workflowId)).isEmpty();
    }
}
