package com.ieum.api.chat.dto;

import com.ieum.api.chat.domain.ChatMessage;
import com.ieum.api.chat.domain.MessageType;
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

    public static ChatResponse from(ChatMessage message) {
        return ChatResponse.builder()
            .messageId(message.getId())
            .sessionId(message.getSession().getId())
            .senderType(message.getSenderType())
            .content(message.getContent())
            .tokens(new TokenUsage(
                message.getInputTokens() != null ? message.getInputTokens() : 0,
                message.getOutputTokens() != null ? message.getOutputTokens() : 0
            ))
            .build();
    }
}
