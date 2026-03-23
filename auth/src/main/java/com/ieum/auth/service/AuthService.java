package com.ieum.auth.service;

import com.ieum.auth.domain.AuthProvider;
import com.ieum.auth.domain.OAuthAuthorizationCode;
import com.ieum.auth.domain.RefreshToken;
import com.ieum.auth.domain.User;
import com.ieum.auth.domain.UserRole;
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

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    @Transactional
    public User register(String email, String password, String name) {
        if (userRepository.existsByEmail(email)) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        User user = User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode(password))
            .name(name)
            .provider(AuthProvider.LOCAL)
            .role(UserRole.ROLE_USER)
            .build();
        return userRepository.save(user);
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

        long expiresIn = jwtTokenProvider.getExpiration(accessToken) / 1000;
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
        long expiresIn = jwtTokenProvider.getExpiration(newAccessToken) / 1000;

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
