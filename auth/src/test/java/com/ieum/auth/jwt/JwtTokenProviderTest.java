package com.ieum.auth.jwt;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    // HS256은 최소 256bit(32바이트) 키 필요
    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hs256-algorithm";
    private static final long EXPIRATION = 1000 * 60 * 30L;           // 30분
    private static final long REFRESH_EXPIRATION = 1000 * 60 * 60 * 24 * 7L; // 7일

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "secret", SECRET);
        ReflectionTestUtils.setField(jwtTokenProvider, "expiration", EXPIRATION);
        ReflectionTestUtils.setField(jwtTokenProvider, "refreshExpiration", REFRESH_EXPIRATION);
        jwtTokenProvider.init();
    }

    @Test
    void generateAccessToken_claimsAreCorrect() {
        UUID userId = UUID.randomUUID();
        String email = "user@example.com";
        String role = "ROLE_USER";

        String token = jwtTokenProvider.generateAccessToken(userId, email, role);

        assertThat(jwtTokenProvider.getUserIdFromToken(token)).isEqualTo(userId);
        assertThat(jwtTokenProvider.getEmailFromToken(token)).isEqualTo(email);
        assertThat(jwtTokenProvider.getRoleFromToken(token)).isEqualTo(role);
    }

    @Test
    void generateRefreshToken_subjectIsUserId() {
        UUID userId = UUID.randomUUID();

        String token = jwtTokenProvider.generateRefreshToken(userId);

        assertThat(jwtTokenProvider.getUserIdFromToken(token)).isEqualTo(userId);
    }

    @Test
    void validateToken_validToken_returnsTrue() {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenProvider.generateAccessToken(userId, "test@test.com", "ROLE_USER");

        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    }

    @Test
    void validateToken_expiredToken_throwsTokenExpired() {
        JwtTokenProvider shortLivedProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(shortLivedProvider, "secret", SECRET);
        ReflectionTestUtils.setField(shortLivedProvider, "expiration", 1L); // 1ms
        ReflectionTestUtils.setField(shortLivedProvider, "refreshExpiration", REFRESH_EXPIRATION);
        shortLivedProvider.init();

        String token = shortLivedProvider.generateAccessToken(UUID.randomUUID(), "test@test.com", "ROLE_USER");

        // 토큰이 만료될 때까지 대기
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}

        assertThatThrownBy(() -> jwtTokenProvider.validateToken(token))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TOKEN_EXPIRED));
    }

    @Test
    void validateToken_tamperedToken_throwsTokenInvalid() {
        String token = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), "test@test.com", "ROLE_USER");
        String tampered = token + "tampered";

        assertThatThrownBy(() -> jwtTokenProvider.validateToken(tampered))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TOKEN_INVALID));
    }

    @Test
    void getExpiration_returnsPositiveValue() {
        String token = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), "test@test.com", "ROLE_USER");

        long remaining = jwtTokenProvider.getExpiration(token);

        assertThat(remaining).isPositive();
        assertThat(remaining).isLessThanOrEqualTo(EXPIRATION);
    }
}
