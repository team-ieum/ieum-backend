package com.ieum.api.webhookcredential.domain;

import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자별 Slack/Discord 송신 웹훅 자격증명.
 *
 * <p>OAuth(connected_accounts)가 아닌 Incoming Webhook URL 기반 서비스 전용. 워크플로우 AI 노드가
 * 'slack'/'discord' 도구를 사용할 때, 노드 config의 webhookCredentialId(이 엔티티의 PK)로 조회되어
 * 실행 시점에 복호화된 webhook_url을 도구 config에 주입한다. URL은 그 자체로 비밀(노출 시 누구나
 * 발송 가능)이므로 AES로 암호화하여 저장한다(평문 저장 금지).
 */
@Entity
@Table(
        name = "webhook_credentials",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_webhook_credential_user_display_name",
                columnNames = {"user_id", "display_name"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookCredential extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookProvider provider;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** Incoming Webhook URL을 AES 암호화한 값. */
    @Column(name = "encrypted_webhook_url", nullable = false, columnDefinition = "TEXT")
    private String encryptedWebhookUrl;

    /** Slack 등에서 기본 채널을 덮어쓸 때 사용하는 선택 값. */
    @Column(name = "default_channel", length = 200)
    private String defaultChannel;

    @Column(nullable = false)
    private boolean enabled;

    @Builder
    private WebhookCredential(UUID userId, WebhookProvider provider, String displayName,
                             String encryptedWebhookUrl, String defaultChannel, boolean enabled) {
        this.userId = userId;
        this.provider = provider;
        this.displayName = displayName;
        this.encryptedWebhookUrl = encryptedWebhookUrl;
        this.defaultChannel = defaultChannel;
        this.enabled = enabled;
    }
}
