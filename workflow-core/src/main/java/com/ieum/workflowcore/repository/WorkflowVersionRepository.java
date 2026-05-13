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

    Optional<WorkflowVersion> findFirstByWorkflowIdOrderByVersionDesc(UUID workflowId);

    /** 여러 워크플로우 ID의 최신 버전을 한 번에 조회 (N+1 방지) */
    @Query("SELECT wv FROM WorkflowVersion wv WHERE wv.workflow.id IN :workflowIds AND wv.version = " +
           "(SELECT MAX(wv2.version) FROM WorkflowVersion wv2 WHERE wv2.workflow.id = wv.workflow.id)")
    List<WorkflowVersion> findLatestByWorkflowIds(@Param("workflowIds") List<UUID> workflowIds);

    /** 버전 번호 동시성 안전 계산용 */
    @Query("SELECT COALESCE(MAX(wv.version), 0) FROM WorkflowVersion wv WHERE wv.workflow.id = :workflowId")
    int findMaxVersionByWorkflowId(@Param("workflowId") UUID workflowId);

    List<WorkflowVersion> findByWorkflowId(UUID workflowId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM WorkflowVersion wv WHERE wv.workflow = :workflow")
    void deleteByWorkflow(@Param("workflow") Workflow workflow);
}
