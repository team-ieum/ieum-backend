package com.ieum.api.mcp.dto;

import com.ieum.api.mcp.domain.McpServerCatalog;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MCP 서버 카탈로그 응답 DTO.
 *
 * <p>보안상 인증 헤더(토큰 포함 가능)는 절대 응답에 포함하지 않고, 설정 여부(hasHeaders)만 노출한다.
 */
public record McpServerResponse(
        UUID id,
        String displayName,
        String description,
        String serverUrl,
        boolean hasHeaders,
        boolean enabled,
        LocalDateTime createdAt
) {
    public static McpServerResponse from(McpServerCatalog catalog) {
        return new McpServerResponse(
                catalog.getId(),
                catalog.getDisplayName(),
                catalog.getDescription(),
                catalog.getServerUrl(),
                catalog.getEncryptedHeaders() != null && !catalog.getEncryptedHeaders().isBlank(),
                catalog.isEnabled(),
                catalog.getCreatedAt()
        );
    }
}
