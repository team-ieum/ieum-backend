package com.ieum.auth.domain;

import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Slack 워크스페이스(team) 설치 하나. 봇 토큰은 워크스페이스 단위이고 refresh는 1회용이라 링크한 IEUM 사용자들이
 * 이 행을 공유한다(spec D2). 토큰은 AesEncryptor 암호문, rotation이 꺼져 있으면 refresh·만료가 null이다.
 */
@Entity
@Table(name = "slack_installations",
    uniqueConstraints = @UniqueConstraint(name = "uk_slack_installations_team_id", columnNames = "team_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlackInstallation extends BaseEntity {

    /** 만료까지 이만큼 이하로 남으면 갱신한다 */
    public static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "team_id", nullable = false, length = 32)
    private String teamId;

    @Column(name = "team_name")
    private String teamName;

    @Column(name = "bot_user_id", length = 32)
    private String botUserId;

    @Column(name = "app_id", length = 32)
    private String appId;

    @Column(name = "bot_token", nullable = false, columnDefinition = "TEXT")
    private String botToken;

    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;

    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    @Column(length = 1000)
    private String scopes;

    public SlackInstallation(String teamId) {
        this.teamId = teamId;
    }

    /** 설치 응답(oauth.v2.access)으로 덮어쓴다. 토큰은 암호문, refresh·만료는 응답에 없으면 null */
    public void applyInstall(String botToken, String refreshToken, LocalDateTime tokenExpiresAt,
                             String scopes, String teamName, String botUserId, String appId) {
        this.botToken = botToken;
        this.refreshToken = refreshToken;
        this.tokenExpiresAt = tokenExpiresAt;
        this.scopes = scopes;
        this.teamName = teamName;
        this.botUserId = botUserId;
        this.appId = appId;
    }

    /** 갱신 응답으로 덮어쓴다 — refresh는 1회용이라 access와 함께 반드시 바뀐다 */
    public void rotate(String botToken, String refreshToken, LocalDateTime tokenExpiresAt) {
        this.botToken = botToken;
        this.refreshToken = refreshToken;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    /** 만료 시각이 있고 남은 시간이 5분 이하(이미 지남 포함)면 true. 만료가 없으면(rotation 꺼짐) false */
    public boolean needsRefresh(LocalDateTime now) {
        return tokenExpiresAt != null && !now.isBefore(tokenExpiresAt.minus(REFRESH_MARGIN));
    }
}
