package com.ieum.api.chat.dto;

/**
 * ieum-agent {@code POST /v1/chat} 요청의 {@code availableMcpServers} 항목.
 *
 * <p>사용자가 보유한 MCP 서버 카탈로그 메타데이터로, 생성/수정 단계에서 Designer가 적절한
 * AI 노드에 {@code mcp} 도구를 배정하는 데 사용한다. serverUrl/헤더 등 민감 정보는 제외하고
 * 매칭·식별에 필요한 정보(catalogId/name/description)만 전달한다. 필드명은 agent의
 * {@code McpServerMeta} 스키마(catalogId/name/description)와 일치한다.
 */
public record AvailableMcpServer(
    String catalogId,
    String name,
    String description
) {}
