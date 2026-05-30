package com.ieum.api.mcp.domain;

import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 사용자별 MCP 서버 카탈로그.
 *
 * <p>워크플로우 AI 노드가 'mcp' 도구를 사용할 때, 노드 config의 catalogId(이 엔티티의 PK)로
 * 조회되어 실행 시점에 serverUrl/헤더를 주입한다. 헤더는 인증 토큰을 포함할 수 있으므로
 * AES로 암호화하여 저장한다(평문 저장 금지).
 */
@Entity
@Table(
        name = "mcp_server_catalogs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mcp_server_user_display_name",
                columnNames = {"user_id", "display_name"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class McpServerCatalog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(length = 500)
    private String description;

    @Column(name = "server_url", nullable = false, columnDefinition = "TEXT")
    private String serverUrl;

    /** 인증 헤더 JSON(Map<String,String>)을 AES 암호화한 값. 헤더가 없으면 null. */
    @Column(name = "encrypted_headers", columnDefinition = "TEXT")
    private String encryptedHeaders;

    @Column(nullable = false)
    private boolean enabled;

    @Builder
    private McpServerCatalog(UUID userId, String displayName, String description,
                            String serverUrl, String encryptedHeaders, boolean enabled) {
        this.userId = userId;
        this.displayName = displayName;
        this.description = description;
        this.serverUrl = serverUrl;
        this.encryptedHeaders = encryptedHeaders;
        this.enabled = enabled;
    }
}
