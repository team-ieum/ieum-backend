package com.ieum.workflowcore.chat.repository;

import com.ieum.workflowcore.chat.domain.ChatMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    /** 세션의 메시지를 페이지네이션으로 조회 (최신순) */
    Page<ChatMessage> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Pageable pageable);

    /** 에이전트에 전달할 대화 히스토리 — 오래된 순으로 전체 조회 */
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /** 워크플로우 삭제 시 호출자 트랜잭션 안에서 부른다 — session_id FK가 있어 세션보다 먼저 지운다. */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM ChatMessage m WHERE m.session.id IN "
        + "(SELECT s.id FROM ChatSession s WHERE s.workflowId = :workflowId)")
    void deleteByWorkflowId(@Param("workflowId") UUID workflowId);
}
