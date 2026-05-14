package com.ieum.api.chat.domain;

import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 워크플로우 기반 채팅 세션.
 * 한 사용자가 하나의 워크플로우와 나누는 대화 단위.
 */
@Entity
@Table(
    name = "chat_sessions",
    indexes = {
        @Index(name = "idx_chat_sessions_workflow_id", columnList = "workflow_id"),
        @Index(name = "idx_chat_sessions_user_id", columnList = "user_id")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    /** 연결된 워크플로우 ID */
    @Column(name = "workflow_id", columnDefinition = "uuid", nullable = false)
    private UUID workflowId;

    /** 세션 소유자 */
    @Column(name = "user_id", columnDefinition = "uuid", nullable = false)
    private UUID userId;

    /** 세션 제목 (첫 메시지 기반 자동 생성) */
    @Column(name = "title", length = 255)
    private String title;

    @Builder
    private ChatSession(UUID workflowId, UUID userId, String title) {
        this.workflowId = workflowId;
        this.userId = userId;
        this.title = title;
    }

    public void updateTitle(String title) {
        this.title = title;
    }
}
