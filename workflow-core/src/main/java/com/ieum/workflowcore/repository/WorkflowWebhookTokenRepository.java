package com.ieum.workflowcore.repository;

import com.ieum.workflowcore.domain.WorkflowWebhookToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowWebhookTokenRepository extends JpaRepository<WorkflowWebhookToken, UUID> {

    Optional<WorkflowWebhookToken> findByWorkflowId(UUID workflowId);

    /** 워크플로우 삭제 시 호출자 트랜잭션 안에서 부른다. */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM WorkflowWebhookToken t WHERE t.workflowId = :workflowId")
    void deleteByWorkflowId(@Param("workflowId") UUID workflowId);
}
