package com.ieum.api.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class AlertCooldownStoreTest {

    private ValueOperations<String, String> valueOperations;
    private AlertCooldownStore store;

    private final UUID workflowId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new AlertCooldownStore(redisTemplate);
    }

    @Test
    @DisplayName("첫 실패는 발신 허용되고 워크플로우별 키로 5분 쿨다운을 세운다")
    void tryAcquire_firstFailure_allowsAndSetsCooldown() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        assertThat(store.tryAcquire(workflowId)).isTrue();

        verify(valueOperations).setIfAbsent(
                "alert:cooldown:" + workflowId, "1", Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("쿨다운 중인 두 번째 실패는 발신되지 않는다")
    void tryAcquire_withinCooldown_denies() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        assertThat(store.tryAcquire(workflowId)).isFalse();
    }

    @Test
    @DisplayName("Redis 장애 시에는 쿨다운 없이 발신한다 — 알림을 잃는 것보다 낫다")
    void tryAcquire_redisFailure_allows() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertThat(store.tryAcquire(workflowId)).isTrue();
    }
}
