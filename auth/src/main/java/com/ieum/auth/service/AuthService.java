package com.ieum.auth.service;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.OAuthAuthorizationCode;
import com.ieum.auth.domain.RefreshToken;
import com.ieum.auth.domain.User;
import com.ieum.auth.domain.UserRole;
import com.ieum.auth.domain.VerificationPurpose;
import com.ieum.auth.dto.TokenInfo;
import com.ieum.auth.jwt.JwtTokenProvider;
import com.ieum.auth.repository.OAuthAuthorizationCodeRepository;
import com.ieum.auth.repository.RefreshTokenRepository;
import com.ieum.auth.repository.UserRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OAuthAuthorizationCodeRepository oAuthAuthorizationCodeRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final LoginAttemptService loginAttemptService;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    @Transactional
    public User register(String email, String password, String name) {
        if (userRepository.existsByEmail(email)) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        // 중복 검사 뒤에 소비한다 — 중복 이메일 요청이 인증 표시를 헛되이 지우지 않도록.
        emailVerificationService.consumeVerification(email, VerificationPurpose.SIGNUP);
        User user = User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode(password))
            .name(name)
            .provider(AuthProvider.LOCAL)
            .role(UserRole.ROLE_USER)
            .build();
        return userRepository.save(user);
    }

    /**
     * PASSWORD_RESET 인증을 마친 이메일의 비밀번호를 바꾸고 Refresh Token을 지워 기존 세션을 끊는다.
     *
     * <p>인증코드는 LOCAL 계정에만 발송되므로 인증 표시가 있으면 LOCAL 계정이 있다. 그 사이 계정이
     * 사라졌거나 소셜 계정으로 바뀐 경우만 NOT_FOUND다.
     */
    @Transactional
    public void resetPassword(String email, String newPassword) {
        emailVerificationService.consumeVerification(email, VerificationPurpose.PASSWORD_RESET);

        User user = userRepository.findByEmail(email)
            .filter(found -> found.getProvider() == AuthProvider.LOCAL)
            .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        user.updatePassword(passwordEncoder.encode(newPassword));
        // ponytail: 이미 발급된 Access Token은 만료(30분)까지 유효하다. 즉시 차단이 필요하면 토큰 블랙리스트 추가.
        refreshTokenRepository.deleteById(user.getId().toString());
        // 메일 소유를 증명했으므로 로그인 잠금도 푼다 — 제3자가 일부러 잠근 계정을 본인이 바로 복구할 수 있게.
        loginAttemptService.reset(user.getEmail());
    }

    /**
     * 로그인 상태에서 현재 비밀번호를 확인한 뒤 바꾸고, Refresh Token을 지워 모든 기기의 세션을 끊는다.
     * 비밀번호를 잊은 경우는 이메일 인증 기반 {@link #resetPassword}가 맡는다.
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        if (user.getProvider() != AuthProvider.LOCAL) {
            throw new CustomException(ErrorCode.PASSWORD_CHANGE_NOT_SUPPORTED);
        }
        // 탈취된 Access Token으로 현재 비밀번호를 대입하지 못하게 로그인 실패 카운터를 함께 쓴다.
        loginAttemptService.checkNotLocked(user.getEmail());
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            loginAttemptService.recordFailure(user.getEmail());
            throw new CustomException(ErrorCode.CURRENT_PASSWORD_MISMATCH);
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new CustomException(ErrorCode.PASSWORD_SAME_AS_CURRENT);
        }

        user.updatePassword(passwordEncoder.encode(newPassword));
        refreshTokenRepository.deleteById(user.getId().toString());
        loginAttemptService.reset(user.getEmail());
    }

    @Transactional
    public TokenInfo login(String email, String password) {
        // 잠금 확인을 비밀번호 검증보다 먼저 한다 — 잠금 중에는 정답 여부도 알려주지 않는다.
        loginAttemptService.checkNotLocked(email);

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            // 없는 이메일도 똑같이 센다 — 잠김 응답으로 가입 여부가 드러나지 않게.
            loginAttemptService.recordFailure(email);
            throw new CustomException(ErrorCode.INVALID_CREDENTIALS);
        }
        loginAttemptService.reset(email);

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        refreshTokenRepository.save(RefreshToken.builder()
            .id(user.getId().toString())
            .token(refreshToken)
            .ttl(refreshExpiration / 1000)
            .build());

        long expiresIn = jwtTokenProvider.getAccessTokenExpiration();
        return new TokenInfo(accessToken, refreshToken, expiresIn);
    }

    @Transactional
    public TokenInfo refresh(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new CustomException(ErrorCode.TOKEN_INVALID);
        }

        UUID userId = jwtTokenProvider.getUserIdFromToken(refreshToken);

        RefreshToken stored = refreshTokenRepository.findById(userId.toString())
            .orElseThrow(() -> new CustomException(ErrorCode.TOKEN_INVALID));

        if (!stored.getToken().equals(refreshToken)) {
            throw new CustomException(ErrorCode.TOKEN_INVALID);
        }

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        String newAccessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        long expiresIn = jwtTokenProvider.getAccessTokenExpiration();

        return new TokenInfo(newAccessToken, refreshToken, expiresIn);
    }

    @Transactional
    public TokenInfo exchangeOAuthCode(String code) {
        OAuthAuthorizationCode authorizationCode = oAuthAuthorizationCodeRepository.findById(code)
            .orElseThrow(() -> new CustomException(ErrorCode.TOKEN_INVALID));

        oAuthAuthorizationCodeRepository.deleteById(code);

        return new TokenInfo(
            authorizationCode.getAccessToken(),
            authorizationCode.getRefreshToken(),
            authorizationCode.getExpiresIn()
        );
    }

    @Transactional
    public void logout(String refreshToken) {
        try {
            if (jwtTokenProvider.validateToken(refreshToken)) {
                UUID userId = jwtTokenProvider.getUserIdFromToken(refreshToken);
                refreshTokenRepository.deleteById(userId.toString());
            }
        } catch (CustomException ignored) {
            // 유효하지 않은 토큰도 멱등성을 위해 정상 처리
        }
    }
}
