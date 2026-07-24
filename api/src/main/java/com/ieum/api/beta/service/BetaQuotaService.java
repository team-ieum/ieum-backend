package com.ieum.api.beta.service;

import com.ieum.api.beta.config.BetaPlatformKeyProperties;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 베타 플랫폼 키 사용량 쿼터 관리 (Redis).
 *
 * <p>두 축을 독립적으로 게이트한다.
 * <ul>
 *   <li>일일 호출 횟수 — {@code beta:calls:{userId}:{yyyyMMdd}}. 호출 전 INCR, 첫 증가 시 TTL 48h.
 *       쿼터 초과 시 사전 차단.</li>
 *   <li>누적 토큰 사용량 — {@code beta:tokens:{userId}} (TTL 없음). 호출 전엔 현재값이 예산 미만인지 확인만
 *       하고, 호출 후 응답 usage의 totalTokens만큼 사후 가산(INCRBY)한다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BetaQuotaService {

    private static final DateTimeFormatter DAILY_KEY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Duration DAILY_KEY_TTL = Duration.ofHours(48);

    private final StringRedisTemplate redisTemplate;
    private final BetaPlatformKeyProperties properties;

    /**
     * 호출 전 게이트. 토큰 예산을 먼저 확인하고(초과 시 일일 카운터는 건드리지 않음),
     * 이후 일일 호출 카운터를 증가시키며 쿼터를 확인한다. 둘 중 하나라도 초과하면 차단한다.
     */
    public void checkQuota(UUID userId) {
        checkTokenBudget(userId);
        incrementAndCheckDailyCalls(userId);
    }

    /** 누적 토큰 사용량이 예산 이상이면 차단한다 (증가 없이 조회만). */
    public void checkTokenBudget(UUID userId) {
        if (getUsedTokens(userId) >= properties.getTokenBudget()) {
            throw new CustomException(ErrorCode.BETA_QUOTA_EXCEEDED);
        }
    }

    /** 일일 호출 카운터를 INCR하고, 첫 증가라면 TTL 48h를 설정한다. 쿼터 초과 시 차단한다. */
    public void incrementAndCheckDailyCalls(UUID userId) {
        String key = dailyCallsKey(userId);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, DAILY_KEY_TTL);
        }
        if (count != null && count > properties.getDailyCallQuota()) {
            throw new CustomException(ErrorCode.BETA_QUOTA_EXCEEDED);
        }
    }

    /** 응답 usage의 totalTokens만큼 누적 토큰 사용량에 사후 가산한다. */
    public void addUsedTokens(UUID userId, long totalTokens) {
        if (totalTokens <= 0) {
            return;
        }
        redisTemplate.opsForValue().increment(tokenKey(userId), totalTokens);
    }

    /** 현재까지 누적된 토큰 사용량. 저장된 값이 없으면 0. */
    public long getUsedTokens(UUID userId) {
        String value = redisTemplate.opsForValue().get(tokenKey(userId));
        return value == null ? 0L : Long.parseLong(value);
    }

    /** 토큰 예산 대비 사용률(%). usage 조회 API에서 사용. */
    public double getTokenUsagePercentage(UUID userId) {
        long budget = properties.getTokenBudget();
        if (budget <= 0) {
            return 100.0;
        }
        return Math.min(100.0, (getUsedTokens(userId) * 100.0) / budget);
    }

    /** 오늘 남은 호출 가능 횟수. usage 조회 API에서 사용. */
    public long getRemainingDailyCalls(UUID userId) {
        String value = redisTemplate.opsForValue().get(dailyCallsKey(userId));
        long used = value == null ? 0L : Long.parseLong(value);
        return Math.max(0L, properties.getDailyCallQuota() - used);
    }

    private String dailyCallsKey(UUID userId) {
        return "beta:calls:%s:%s".formatted(userId, LocalDate.now().format(DAILY_KEY_DATE_FORMAT));
    }

    private String tokenKey(UUID userId) {
        return "beta:tokens:" + userId;
    }
}
