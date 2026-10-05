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
    }

    @Transactional
    public TokenInfo login(String email, String password) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new CustomException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new CustomException(ErrorCode.INVALID_CREDENTIALS);
        }

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
