package com.ieum.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class DefaultIdempotencyStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private DefaultIdempotencyStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        store = new DefaultIdempotencyStore(redisTemplate);
    }

    @Test
    @DisplayName("첫 markInFlight는 true(마커를 새로 세움)를 반환한다")
    void markInFlight_firstCall_returnsTrue() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        boolean result = store.markInFlight("key-1", Duration.ofMinutes(5));

        assertThat(result).isTrue();
        verify(valueOperations).setIfAbsent("idem:key-1", "1", Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("두 번째 markInFlight는 false(이미 마커 있음)를 반환한다")
    void markInFlight_secondCall_returnsFalse() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        boolean result = store.markInFlight("key-1", Duration.ofMinutes(5));

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Redis 예외 시 markInFlight는 true(진행 허용)를 반환한다")
    void markInFlight_redisFailure_returnsTrue() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        boolean result = store.markInFlight("key-1", Duration.ofMinutes(5));

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("clearInFlight는 idem: 접두사가 붙은 키를 삭제한다")
    void clearInFlight_deletesPrefixedKey() {
        store.clearInFlight("key-1");

        verify(redisTemplate).delete("idem:key-1");
    }

    @Test
    @DisplayName("Redis 예외 시 clearInFlight는 예외를 전파하지 않는다")
    void clearInFlight_redisFailure_doesNotThrow() {
        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("redis down"));

        store.clearInFlight("key-1");
        // 예외 없이 통과하면 성공
    }
}
