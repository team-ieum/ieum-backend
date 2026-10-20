package com.ieum.auth.service;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoginAttemptServiceTest {

    private static final String FAIL_KEY = "login:fail:user@example.com";
    private static final String LOCK_KEY = "login:lock:user@example.com";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private LoginAttemptService loginAttemptService;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
    }

    @Test
    void checkNotLocked_locked_throwsLoginLocked() {
        given(redisTemplate.hasKey(LOCK_KEY)).willReturn(true);

        assertThatThrownBy(() -> loginAttemptService.checkNotLocked("user@example.com"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.LOGIN_LOCKED));
    }

    @Test
    void checkNotLocked_notLocked_passes() {
        given(redisTemplate.hasKey(LOCK_KEY)).willReturn(false);

        assertThatCode(() -> loginAttemptService.checkNotLocked("user@example.com")).doesNotThrowAnyException();
    }

    @Test
    void checkNotLocked_normalizesEmail() {
        given(redisTemplate.hasKey(LOCK_KEY)).willReturn(true);

        // 대소문자·공백을 바꿔도 같은 잠금 키를 본다 — 우회 불가
        assertThatThrownBy(() -> loginAttemptService.checkNotLocked("  User@Example.COM "))
                .isInstanceOf(CustomException.class);
    }

    @Test
    void recordFailure_firstFailure_setsWindowTtl() {
        given(valueOps.increment(FAIL_KEY)).willReturn(1L);

        loginAttemptService.recordFailure("user@example.com");

        then(redisTemplate).should().expire(FAIL_KEY, LoginAttemptService.FAILURE_WINDOW);
        then(valueOps).should(never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void recordFailure_belowMax_doesNotLock() {
        given(valueOps.increment(FAIL_KEY)).willReturn(4L);

        assertThatCode(() -> loginAttemptService.recordFailure("user@example.com")).doesNotThrowAnyException();

        then(redisTemplate).should(never()).expire(anyString(), any(Duration.class));
        then(valueOps).should(never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void recordFailure_fifthFailure_locksFor24HoursAndThrows() {
        given(valueOps.increment(FAIL_KEY)).willReturn(5L);

        assertThatThrownBy(() -> loginAttemptService.recordFailure("user@example.com"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.LOGIN_LOCKED));

        then(valueOps).should().set(LOCK_KEY, "1", LoginAttemptService.LOCK_DURATION);
        then(redisTemplate).should().delete(FAIL_KEY);
    }

    @Test
    void reset_deletesFailureCountAndLock() {
        loginAttemptService.reset("User@Example.com");

        then(redisTemplate).should().delete(List.of(FAIL_KEY, LOCK_KEY));
    }
}
