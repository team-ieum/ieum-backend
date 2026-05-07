package com.ieum.workflowcore.repository;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, UUID> {

    /**
     * 워크플로우의 최신 버전을 조회한다.
     * version 번호 내림차순 기준 첫 번째 레코드를 반환한다.
     */
    @Query("SELECT wv FROM WorkflowVersion wv WHERE wv.workflow.id = :workflowId ORDER BY wv.version DESC LIMIT 1")
    Optional<WorkflowVersion> findLatestByWorkflowId(@Param("workflowId") UUID workflowId);

    List<WorkflowVersion> findByWorkflowId(UUID workflowId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM WorkflowVersion wv WHERE wv.workflow = :workflow")
    void deleteByWorkflow(@Param("workflow") Workflow workflow);
}
