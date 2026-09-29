package com.ieum.api.beta.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ieum.api.beta.config.BetaPlatformKeyProperties;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class BetaQuotaServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private BetaPlatformKeyProperties properties;
    private BetaQuotaService service;

    private final UUID userId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        properties = new BetaPlatformKeyProperties();
        properties.setDailyCallQuota(30);
        properties.setTokenBudget(5_000_000L);

        service = new BetaQuotaService(redisTemplate, properties);
    }

    @Test
    @DisplayName("일일 호출 - INCR과 첫 EXPIRE(48h=172800초)를 스크립트 한 번으로 실행한다")
    void incrementAndCheckDailyCalls_runsIncrementScriptOnce() {
        stubIncrement(1L);

        service.incrementAndCheckDailyCalls(userId);

        verify(redisTemplate).execute(BetaQuotaService.INCREMENT_DAILY_CALL, List.of(dailyKey()), "172800");
    }

    @Test
    @DisplayName("일일 호출 - 개별 INCR/EXPIRE를 따로 호출하지 않는다(TTL 없는 키가 남는 창 제거)")
    void incrementAndCheckDailyCalls_doesNotCallIncrementOrExpireSeparately() {
        stubIncrement(1L);

        service.incrementAndCheckDailyCalls(userId);

        verify(valueOperations, never()).increment(anyString());
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("일일 호출 - 쿼터 이내면 예외 없음")
    void incrementAndCheckDailyCalls_withinQuota_noException() {
        stubIncrement(30L);

        service.incrementAndCheckDailyCalls(userId);
    }

    @Test
    @DisplayName("일일 호출 - 쿼터 초과 시 BETA_QUOTA_EXCEEDED")
    void incrementAndCheckDailyCalls_exceeded_throws() {
        stubIncrement(31L);

        assertThatThrownBy(() -> service.incrementAndCheckDailyCalls(userId))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BETA_QUOTA_EXCEEDED);
    }

    @Test
    @DisplayName("일일 호출 - 원자성: 초과로 거부된 호출은 DECR로 되돌려 카운터를 소모하지 않는다")
    void incrementAndCheckDailyCalls_exceeded_decrementsBack() {
        stubIncrement(31L);

        assertThatThrownBy(() -> service.incrementAndCheckDailyCalls(userId))
                .isInstanceOf(CustomException.class);

        verify(valueOperations).decrement(dailyKey());
    }

    @Test
    @DisplayName("토큰 예산 - 사용량이 예산 미만이면 통과")
    void checkTokenBudget_withinBudget_passes() {
        when(valueOperations.get(tokenKey())).thenReturn("1000000");

        service.checkTokenBudget(userId);
    }

    @Test
    @DisplayName("토큰 예산 - 사용량이 예산에 도달하면 BETA_QUOTA_EXCEEDED")
    void checkTokenBudget_atBudget_throws() {
        when(valueOperations.get(tokenKey())).thenReturn("5000000");

        assertThatThrownBy(() -> service.checkTokenBudget(userId))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BETA_QUOTA_EXCEEDED);
    }

    @Test
    @DisplayName("토큰 사용량 - 저장된 값 없으면 0으로 취급")
    void getUsedTokens_noValue_returnsZero() {
        when(valueOperations.get(tokenKey())).thenReturn(null);

        assertThat(service.getUsedTokens(userId)).isZero();
    }

    @Test
    @DisplayName("토큰 사용량 - 응답 usage로 사후 가산(INCRBY)")
    void addUsedTokens_incrementsRedis() {
        service.addUsedTokens(userId, 1234L);

        verify(valueOperations).increment(tokenKey(), 1234L);
    }

    @Test
    @DisplayName("토큰 사용량 - 0 이하이면 INCRBY 호출하지 않음")
    void addUsedTokens_zeroOrLess_doesNothing() {
        service.addUsedTokens(userId, 0L);

        verify(valueOperations, never()).increment(anyString(), anyLong());
    }

    @Test
    @DisplayName("토큰 사용률 - 예산 대비 퍼센티지 계산")
    void getTokenUsagePercentage_computesPercentage() {
        when(valueOperations.get(tokenKey())).thenReturn("2500000");

        assertThat(service.getTokenUsagePercentage(userId)).isEqualTo(50.0);
    }

    @Test
    @DisplayName("일일 잔여 호출 - 쿼터에서 사용량을 뺀 값")
    void getRemainingDailyCalls_computesRemaining() {
        when(valueOperations.get(dailyKey())).thenReturn("10");

        assertThat(service.getRemainingDailyCalls(userId)).isEqualTo(20L);
    }

    @Test
    @DisplayName("checkQuota - 토큰/일일 모두 이내면 통과하고 일일 카운터 증가")
    void checkQuota_withinLimits_incrementsDailyCalls() {
        when(valueOperations.get(tokenKey())).thenReturn("0");
        stubIncrement(1L);

        service.checkQuota(userId);

        verify(redisTemplate).execute(BetaQuotaService.INCREMENT_DAILY_CALL, List.of(dailyKey()), "172800");
    }

    @Test
    @DisplayName("checkQuota - 토큰 예산 초과 시 일일 카운터 증가 없이 차단")
    void checkQuota_tokenExceeded_doesNotIncrementDailyCalls() {
        when(valueOperations.get(tokenKey())).thenReturn("5000000");

        assertThatThrownBy(() -> service.checkQuota(userId))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BETA_QUOTA_EXCEEDED);

        verify(redisTemplate, never()).execute(eq(BetaQuotaService.INCREMENT_DAILY_CALL), any(), any());
    }

    @Test
    @DisplayName("releaseDailyCall(key) - 전달받은 키로 환불 스크립트를 한 번 실행한다")
    void releaseDailyCall_runsReleaseScriptOnce() {
        service.releaseDailyCall(dailyKey());

        verify(redisTemplate).execute(BetaQuotaService.RELEASE_DAILY_CALL, List.of(dailyKey()));
    }

    @Test
    @DisplayName("releaseDailyCall(key) - DECR 후 음수 보정을 개별 명령으로 하지 않는다(동시 환불 레이스 제거)")
    void releaseDailyCall_doesNotCallDecrementOrIncrementSeparately() {
        service.releaseDailyCall(dailyKey());

        verify(valueOperations, never()).decrement(anyString());
        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    @DisplayName("checkQuota/incrementAndCheckDailyCalls - 예약에 사용한 일일 카운터 키를 반환한다")
    void checkQuota_returnsUsedDailyKey() {
        when(valueOperations.get(tokenKey())).thenReturn("0");
        stubIncrement(1L);

        String key = service.checkQuota(userId);

        assertThat(key).isEqualTo(dailyKey());
    }

    @Test
    @DisplayName("releaseDailyCall(key) - 자정 경계: reserve가 반환한(어제 날짜) 키를 그대로 DECR하고 " +
            "LocalDate.now()로 재계산한 오늘 키는 건드리지 않는다")
    void releaseDailyCall_midnightBoundary_usesReservedKeyNotToday() {
        String yesterdayKey = "beta:calls:%s:20260101".formatted(userId);

        service.releaseDailyCall(yesterdayKey);

        verify(redisTemplate).execute(BetaQuotaService.RELEASE_DAILY_CALL, List.of(yesterdayKey));
        verify(redisTemplate, never()).execute(BetaQuotaService.RELEASE_DAILY_CALL, List.of(dailyKey()));
    }

    private void stubIncrement(long count) {
        when(redisTemplate.execute(BetaQuotaService.INCREMENT_DAILY_CALL, List.of(dailyKey()), "172800"))
                .thenReturn(count);
    }

    private String dailyKey() {
        return "beta:calls:%s:%s".formatted(userId, LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));
    }

    private String tokenKey() {
        return "beta:tokens:" + userId;
    }
}
