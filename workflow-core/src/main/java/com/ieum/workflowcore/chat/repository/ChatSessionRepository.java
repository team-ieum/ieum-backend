package com.ieum.workflowcore.chat.repository;

import com.ieum.workflowcore.chat.domain.ChatSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    Optional<ChatSession> findByIdAndUserId(UUID id, UUID userId);

    /** 워크플로우 삭제 시 호출자 트랜잭션 안에서 부른다. 메시지를 먼저 지운 뒤 호출한다. */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM ChatSession s WHERE s.workflowId = :workflowId")
    void deleteByWorkflowId(@Param("workflowId") UUID workflowId);
}
