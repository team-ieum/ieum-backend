package com.ieum.auth.service;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.RefreshToken;
import com.ieum.auth.domain.User;
import com.ieum.auth.domain.UserRole;
import com.ieum.auth.dto.TokenInfo;
import com.ieum.auth.jwt.JwtTokenProvider;
import com.ieum.auth.repository.RefreshTokenRepository;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "refreshExpiration", 604800000L);
    }

    // ===== register =====

    @Test
    void register_success() {
        given(userRepository.existsByEmail(anyString())).willReturn(false);
        given(passwordEncoder.encode(anyString())).willReturn("encoded-password");
        User saved = buildUser();
        given(userRepository.save(any(User.class))).willReturn(saved);

        User result = authService.register("test@test.com", "Password1!", "홍길동");

        assertThat(result).isEqualTo(saved);
        then(userRepository).should().save(any(User.class));
    }

    @Test
    void register_duplicateEmail_throwsEmailAlreadyExists() {
        given(userRepository.existsByEmail(anyString())).willReturn(true);

        assertThatThrownBy(() -> authService.register("dup@test.com", "Password1!", "홍길동"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS));

        then(userRepository).should(never()).save(any());
    }

    // ===== login =====

    @Test
    void login_success() {
        User user = buildUser();
        given(userRepository.findByEmail(user.getEmail())).willReturn(Optional.of(user));
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(true);
        given(jwtTokenProvider.generateAccessToken(any(), anyString(), anyString())).willReturn("access-token");
        given(jwtTokenProvider.generateRefreshToken(any())).willReturn("refresh-token");
        given(jwtTokenProvider.getAccessTokenExpiration()).willReturn(1800L);

        TokenInfo result = authService.login(user.getEmail(), "Password1!");

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        assertThat(result.expiresIn()).isEqualTo(1800L);
        then(refreshTokenRepository).should().save(any(RefreshToken.class));
    }

    @Test
    void login_userNotFound_throwsInvalidCredentials() {
        given(userRepository.findByEmail(anyString())).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("notfound@test.com", "Password1!"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentials() {
        User user = buildUser();
        given(userRepository.findByEmail(anyString())).willReturn(Optional.of(user));
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(false);

        assertThatThrownBy(() -> authService.login(user.getEmail(), "WrongPass1!"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    // ===== refresh =====

    @Test
    void refresh_success() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId);
        RefreshToken stored = RefreshToken.builder()
                .id(userId.toString())
                .token("refresh-token")
                .ttl(604800L)
                .build();

        given(jwtTokenProvider.validateToken("refresh-token")).willReturn(true);
        given(jwtTokenProvider.getUserIdFromToken("refresh-token")).willReturn(userId);
        given(refreshTokenRepository.findById(userId.toString())).willReturn(Optional.of(stored));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(jwtTokenProvider.generateAccessToken(any(), anyString(), anyString())).willReturn("new-access-token");
        given(jwtTokenProvider.getAccessTokenExpiration()).willReturn(1800L);

        TokenInfo result = authService.refresh("refresh-token");

        assertThat(result.accessToken()).isEqualTo("new-access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    void refresh_tokenNotInStore_throwsTokenInvalid() {
        UUID userId = UUID.randomUUID();
        given(jwtTokenProvider.validateToken("refresh-token")).willReturn(true);
        given(jwtTokenProvider.getUserIdFromToken("refresh-token")).willReturn(userId);
        given(refreshTokenRepository.findById(userId.toString())).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("refresh-token"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TOKEN_INVALID));
    }

    // ===== logout =====

    @Test
    void logout_validToken_deletesFromStore() {
        UUID userId = UUID.randomUUID();
        given(jwtTokenProvider.validateToken("refresh-token")).willReturn(true);
        given(jwtTokenProvider.getUserIdFromToken("refresh-token")).willReturn(userId);

        authService.logout("refresh-token");

        then(refreshTokenRepository).should().deleteById(userId.toString());
    }

    @Test
    void logout_invalidToken_doesNotThrow() {
        given(jwtTokenProvider.validateToken(anyString()))
                .willThrow(new CustomException(ErrorCode.TOKEN_INVALID));

        assertThatCode(() -> authService.logout("bad-token"))
                .doesNotThrowAnyException();

        then(refreshTokenRepository).should(never()).deleteById(any());
    }

    // ===== 헬퍼 =====

    private User buildUser() {
        return buildUser(UUID.randomUUID());
    }

    private User buildUser(UUID id) {
        return User.builder()
                .id(id)
                .email("user@example.com")
                .passwordHash("encoded-password")
                .name("홍길동")
                .provider(AuthProvider.LOCAL)
                .role(UserRole.ROLE_USER)
                .build();
    }
}
