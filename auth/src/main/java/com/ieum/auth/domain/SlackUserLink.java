package com.ieum.auth.domain;

import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * IEUM 사용자 → Slack 설치 링크. 사용자당 하나(D1) — 여러 워크스페이스로 갈 때 (user_id, installation_id) 유일로 바꾼다.
 * slack_user_id는 설치한 Slack 사용자(authed_user.id) — S2·S3 확장용.
 */
@Entity
@Table(name = "slack_user_links",
    uniqueConstraints = @UniqueConstraint(name = "uk_slack_user_links_user_id", columnNames = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlackUserLink extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "installation_id", nullable = false)
    private SlackInstallation installation;

    @Column(name = "slack_user_id", length = 32)
    private String slackUserId;

    public SlackUserLink(UUID userId, SlackInstallation installation, String slackUserId) {
        this.userId = userId;
        this.installation = installation;
        this.slackUserId = slackUserId;
    }

    public void updateSlackUserId(String slackUserId) {
        this.slackUserId = slackUserId;
    }
}
