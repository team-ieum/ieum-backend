package com.ieum.api.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ChatTurnLockTest {

    private final UUID userId = UUID.randomUUID();
    private final String key = "chat:inflight:" + userId;

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ChatTurnLock lock;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lock = new ChatTurnLock(redisTemplate);
    }

    @Test
    @DisplayName("SET chat:inflight:{userId} 1 NX EX 660 — 성공이면 true, 이미 있으면(false·null) false")
    void tryAcquire_setIfAbsentWithTtl() {
        when(valueOperations.setIfAbsent(key, "1", Duration.ofSeconds(660))).thenReturn(true, false, null);

        assertThat(lock.tryAcquire(userId)).isTrue();
        assertThat(lock.tryAcquire(userId)).isFalse();
        assertThat(lock.tryAcquire(userId)).isFalse();
    }

    @Test
    @DisplayName("해제 — 같은 키를 지운다")
    void release_deletesKey() {
        lock.release(userId);

        verify(redisTemplate).delete(key);
    }
}
