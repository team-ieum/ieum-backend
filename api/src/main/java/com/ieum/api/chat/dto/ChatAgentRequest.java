package com.ieum.api.chat.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * ieum-agent POST /v1/chat 요청 body.
 */
@Getter
@Builder
public class ChatAgentRequest {

    /** 유저 메시지 텍스트 */
    private final String prompt;

    /** 현재 워크플로우 노드 목록 (채팅용으로는 미사용 — null 전달) */
    private final List<Object> currentNodes;

    /** 현재 워크플로우 엣지 목록 (채팅용으로는 미사용 — null 전달) */
    private final List<Object> currentEdges;

    /** 사용 가능한 연동 목록 */
    @Builder.Default
    private final List<Object> availableIntegrations = List.of();

    /** 사용 불가 연동 목록 */
    @Builder.Default
    private final List<Object> unavailableIntegrations = List.of();
}
