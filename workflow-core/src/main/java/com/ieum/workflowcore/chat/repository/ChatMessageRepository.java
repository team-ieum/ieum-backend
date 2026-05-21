package com.ieum.workflowcore.chat.repository;

import com.ieum.workflowcore.chat.domain.ChatMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    /** 세션의 메시지를 페이지네이션으로 조회 (최신순) */
    Page<ChatMessage> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Pageable pageable);

    /** 에이전트에 전달할 대화 히스토리 — 오래된 순으로 전체 조회 */
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
