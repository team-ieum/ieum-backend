package com.ieum.api.chat.domain;

import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채팅 세션 내 개별 메시지.
 */
@Entity
@Table(
    name = "chat_messages",
    indexes = {
        @Index(name = "idx_chat_messages_session_id", columnList = "session_id")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSession session;

    /** 발신자 타입 (USER / AGENT / SYSTEM) */
    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 20)
    private MessageType senderType;

    /** 메시지 본문 */
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    /** AI 응답의 입력 토큰 수 (AGENT 메시지에만 사용) */
    @Column(name = "input_tokens")
    private Integer inputTokens;

    /** AI 응답의 출력 토큰 수 (AGENT 메시지에만 사용) */
    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Builder
    private ChatMessage(
        ChatSession session,
        MessageType senderType,
        String content,
        Integer inputTokens,
        Integer outputTokens
    ) {
        this.session = session;
        this.senderType = senderType;
        this.content = content;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }
}
