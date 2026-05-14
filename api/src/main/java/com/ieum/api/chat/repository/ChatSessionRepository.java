package com.ieum.api.chat.repository;

import com.ieum.api.chat.domain.ChatSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    Optional<ChatSession> findByIdAndUserId(UUID id, UUID userId);

    Optional<ChatSession> findByWorkflowIdAndUserId(UUID workflowId, UUID userId);
}
