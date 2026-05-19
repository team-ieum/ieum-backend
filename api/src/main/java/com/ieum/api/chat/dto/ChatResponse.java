package com.ieum.api.chat.dto;

import com.ieum.workflowcore.chat.domain.ChatMessage;
import com.ieum.workflowcore.chat.domain.MessageType;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

/** 메시지 전송 후 AI 응답 DTO. */
@Getter
@Builder
public class ChatResponse {

    private final UUID messageId;
    private final UUID sessionId;
    private final MessageType senderType;
    private final String content;
    private final TokenUsage tokens;

    /**
     * ChatMessage와 sessionId를 분리해서 받는다.
     * ChatMessage.getSession()은 FetchType.LAZY이므로 트랜잭션 밖에서 접근하면
     * LazyInitializationException이 발생한다.
     * sessionId는 서비스 레이어에서 직접 전달한다.
     */
    public static ChatResponse from(ChatMessage message, UUID sessionId) {
        return ChatResponse.builder()
            .messageId(message.getId())
            .sessionId(sessionId)
            .senderType(message.getSenderType())
            .content(message.getContent())
            .tokens(new TokenUsage(
                message.getInputTokens() != null ? message.getInputTokens() : 0,
                message.getOutputTokens() != null ? message.getOutputTokens() : 0
            ))
            .build();
    }
}
