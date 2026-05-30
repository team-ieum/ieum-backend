package com.ieum.api.chat.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * ieum-agent POST /v1/chat 요청 body.
 *
 * <p>ieum-backend가 DB canonical state 기준으로 currentNodes/currentEdges를 채워 전달하고,
 * 연동 서비스 상태를 available/unavailable 두 목록으로 분리하여 전달한다.
 */
@Getter
@Builder
public class ChatAgentRequest {

    /** 사용자 자연어 입력 */
    private final String prompt;

    /**
     * 현재 워크플로우 노드 목록.
     * null이면 신규 생성, non-null이면 수정 컨텍스트.
     */
    private final List<Object> currentNodes;

    /** 현재 워크플로우 엣지 목록 */
    private final List<Object> currentEdges;

    /** 연동 완료된 서비스 목록 (provider + type + scopes) */
    private final List<IntegrationInfo> availableIntegrations;

    /** 미연동 서비스 목록 (provider + type) */
    private final List<IntegrationInfo> unavailableIntegrations;

    /**
     * 사용자가 보유한 MCP 서버 카탈로그 메타(catalogId/name/description).
     * Designer가 적절한 AI 노드에 mcp 도구를 배정하는 데 사용한다(환각 방지).
     */
    private final List<AvailableMcpServer> availableMcpServers;
}
