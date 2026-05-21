package com.ieum.api.chat.dto;

import com.ieum.workflowcore.chat.domain.ChatMessage;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

/**
 * 채팅 API 최종 응답 DTO.
 *
 * <p>ieum-agent 응답({@link ChatAgentResponse})을 클라이언트용으로 변환한다.
 * type에 따라 포함되는 필드가 다르다:
 *
 * <ul>
 *   <li>{@code WORKFLOW_GENERATED/MODIFIED} — message + nodes + edges (+ changeDescription)</li>
 *   <li>{@code INTEGRATION_REQUIRED} — message + actions (oauthUrl 포함)</li>
 *   <li>{@code CLARIFICATION_NEEDED} — message만</li>
 * </ul>
 */
@Getter
@Builder
public class ChatResponse {

    private final UUID messageId;
    private final UUID sessionId;

    /** 응답 타입 */
    private final AgentResponseType type;

    /** AI 자연어 응답 텍스트 */
    private final String content;

    /** 워크플로우 수정 시 변경 내용 한 줄 요약 (WORKFLOW_MODIFIED 전용) */
    private final String changeDescription;

    /** 생성/수정된 노드 목록 (WORKFLOW_GENERATED/MODIFIED 전용) */
    private final List<Object> nodes;

    /** 생성/수정된 엣지 목록 (WORKFLOW_GENERATED/MODIFIED 전용) */
    private final List<Object> edges;

    /** 프론트엔드 실행 액션 목록 (INTEGRATION_REQUIRED 전용, oauthUrl 포함) */
    private final List<AgentAction> actions;

    private final TokenUsage tokens;

    /**
     * 저장된 ChatMessage와 agent 응답으로부터 최종 응답 DTO를 생성한다.
     *
     * <p>sessionId는 서비스 레이어에서 직접 전달한다.
     * ({@code ChatMessage.getSession()}은 FetchType.LAZY이므로 트랜잭션 밖에서 접근 불가)
     *
     * @param message       저장된 AGENT 메시지 엔티티
     * @param sessionId     세션 ID (LAZY 로딩 회피)
     * @param agentResponse 원본 agent 응답 (nodes/edges/actions 포함)
     */
    public static ChatResponse from(ChatMessage message, UUID sessionId, ChatAgentResponse agentResponse) {
        return ChatResponse.builder()
            .messageId(message.getId())
            .sessionId(sessionId)
            .type(agentResponse.getType())
            .content(agentResponse.getContent())
            .changeDescription(agentResponse.getChangeDescription())
            .nodes(agentResponse.getNodes())
            .edges(agentResponse.getEdges())
            .actions(agentResponse.getActions())
            .tokens(new TokenUsage(
                message.getInputTokens() != null ? message.getInputTokens() : 0,
                message.getOutputTokens() != null ? message.getOutputTokens() : 0
            ))
            .build();
    }

    /**
     * 히스토리 조회용 변환 메서드.
     *
     * <p>저장된 {@link ChatMessage}로부터 응답 DTO를 생성한다.
     * 채팅 히스토리는 {@link AgentResponseType}을 저장하지 않으므로 {@code type}은 {@code null}.
     *
     * @param message   저장된 채팅 메시지 엔티티
     * @param sessionId 세션 ID (LAZY 로딩 회피)
     */
    public static ChatResponse fromHistory(ChatMessage message, UUID sessionId) {
        return ChatResponse.builder()
            .messageId(message.getId())
            .sessionId(sessionId)
            .content(message.getContent())
            .tokens(new TokenUsage(
                message.getInputTokens() != null ? message.getInputTokens() : 0,
                message.getOutputTokens() != null ? message.getOutputTokens() : 0
            ))
            .build();
    }
}
