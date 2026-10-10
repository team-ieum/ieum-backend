package com.ieum.workflowcore.repository;

import com.ieum.workflowcore.domain.NodeTestSample;
import com.ieum.workflowcore.domain.Workflow;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NodeTestSampleRepository extends JpaRepository<NodeTestSample, UUID> {

    Optional<NodeTestSample> findByWorkflowIdAndNodeId(UUID workflowId, String nodeId);

    List<NodeTestSample> findByWorkflowIdAndNodeIdIn(UUID workflowId, Collection<String> nodeIds);

    /** 워크플로우 삭제 전에 부른다 — workflow_id FK가 있고 DB cascade는 없다. 호출자 트랜잭션 안에서 실행된다. */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM NodeTestSample s WHERE s.workflow = :workflow")
    void deleteByWorkflow(@Param("workflow") Workflow workflow);
}
