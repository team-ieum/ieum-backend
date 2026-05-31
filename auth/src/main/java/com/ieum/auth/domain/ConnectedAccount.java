package com.ieum.auth.domain;

import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "connected_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ConnectedAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    /** 외부 provider의 고유 계정 식별자 (Google의 경우 OIDC {@code sub}). 중복 연동 검증용. */
    @Column(name = "provider_account_id", length = 255)
    private String providerAccountId;

    @Column(name = "access_token", nullable = false, columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;

    @Column(length = 500)
    private String scopes;

    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    @Column(name = "refresh_token_expires_at")
    private LocalDateTime refreshTokenExpiresAt;

    public void updateAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    /** provider 계정 식별자(sub)를 설정한다. 기존 데이터 백필 및 신규 연동 시 사용. */
    public void assignProviderAccountId(String providerAccountId) {
        this.providerAccountId = providerAccountId;
    }

    public void updateTokenAndScopes(String accessToken, String scopes) {
        this.accessToken = accessToken;
        this.scopes = scopes;
    }

    public void updateTokens(String accessToken, String refreshToken,
            LocalDateTime tokenExpiresAt, LocalDateTime refreshTokenExpiresAt) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.tokenExpiresAt = tokenExpiresAt;
        this.refreshTokenExpiresAt = refreshTokenExpiresAt;
    }

    public void updateTokensAndScopes(String accessToken, String refreshToken,
            LocalDateTime tokenExpiresAt, LocalDateTime refreshTokenExpiresAt, String scopes) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.tokenExpiresAt = tokenExpiresAt;
        this.refreshTokenExpiresAt = refreshTokenExpiresAt;
        this.scopes = scopes;
    }

    /**
     * Access Token이 곧 만료되어 갱신이 필요한지 확인한다 (5분 threshold).
     *
     * <p>호출 전 {@code tokenExpiresAt != null}을 반드시 확인해야 한다.
     * null인 경우 tokeninfo API 폴백 경로를 사용한다 ({@link com.ieum.auth.service.GoogleTokenService} 참고).
     *
     * @return true면 즉시 갱신 필요 (이미 만료 또는 5분 이내 만료 예정)
     */
    public boolean isAccessTokenExpiringSoon() {
        return LocalDateTime.now().isAfter(tokenExpiresAt.minusMinutes(5));
    }

    /**
     * Refresh Token 자체가 만료되었는지 확인한다.
     *
     * <p>{@code refreshTokenExpiresAt}이 null이면 만료 여부 미확인 상태이므로
     * 만료되지 않은 것으로 간주한다 (기존 데이터 하위 호환).
     *
     * @return true면 Refresh Token 만료 → 사용자 재인증 필요
     */
    public boolean isRefreshTokenExpired() {
        if (refreshTokenExpiresAt == null) {
            return false;
        }
        return LocalDateTime.now().isAfter(refreshTokenExpiresAt);
    }
}