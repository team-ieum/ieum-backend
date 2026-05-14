package com.ieum.api.chat.dto;

import com.ieum.api.chat.domain.ChatSession;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

/** 채팅 세션 정보 DTO. */
@Getter
@Builder
public class ChatSessionResponse {

    private final UUID sessionId;
    private final UUID workflowId;
    private final String title;
    private final LocalDateTime createdAt;

    public static ChatSessionResponse from(ChatSession session) {
        return ChatSessionResponse.builder()
            .sessionId(session.getId())
            .workflowId(session.getWorkflowId())
            .title(session.getTitle())
            .createdAt(session.getCreatedAt())
            .build();
    }
}
