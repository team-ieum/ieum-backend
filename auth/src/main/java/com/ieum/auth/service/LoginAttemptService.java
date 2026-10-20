package com.ieum.auth.service;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 비밀번호 실패 횟수 제한. 이메일 기준으로 {@value #MAX_FAILURES}회 실패하면 24시간 잠근다.
 *
 * <p>가입되지 않은 이메일도 똑같이 센다 — 잠김 여부로 가입 여부를 알아낼 수 없게 하기 위함이다.
 * 카운터는 {@code INCR}로 올려 동시 요청에도 횟수가 새지 않는다.
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    static final int MAX_FAILURES = 5;
    static final Duration FAILURE_WINDOW = Duration.ofHours(24);
    static final Duration LOCK_DURATION = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    /** 잠겨 있으면 {@code LOGIN_LOCKED}. 비밀번호 검증보다 먼저 호출한다. */
    public void checkNotLocked(String email) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey(email)))) {
            throw new CustomException(ErrorCode.LOGIN_LOCKED);
        }
    }

    /** 실패를 기록한다. {@value #MAX_FAILURES}번째 실패면 잠그고 {@code LOGIN_LOCKED}를 던진다. */
    public void recordFailure(String email) {
        String failKey = failKey(email);
        Long failures = redisTemplate.opsForValue().increment(failKey);
        if (failures != null && failures == 1) {
            redisTemplate.expire(failKey, FAILURE_WINDOW);
        }
        if (failures != null && failures >= MAX_FAILURES) {
            redisTemplate.opsForValue().set(lockKey(email), "1", LOCK_DURATION);
            redisTemplate.delete(failKey);
            throw new CustomException(ErrorCode.LOGIN_LOCKED);
        }
    }

    /** 로그인·비밀번호 변경 성공 또는 비밀번호 재설정 시 실패 횟수와 잠금을 지운다. */
    public void reset(String email) {
        redisTemplate.delete(List.of(failKey(email), lockKey(email)));
    }

    private static String failKey(String email) {
        return "login:fail:" + normalize(email);
    }

    private static String lockKey(String email) {
        return "login:lock:" + normalize(email);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
