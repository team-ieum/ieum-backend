package com.ieum.workflowcore.repository;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, UUID> {

    @Query("SELECT e FROM WorkflowExecution e JOIN FETCH e.workflow WHERE e.id = :id")
    Optional<WorkflowExecution> findWithWorkflowById(@Param("id") UUID id);

    List<WorkflowExecution> findByWorkflow(Workflow workflow);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM WorkflowExecution we WHERE we.workflow = :workflow")
    void deleteByWorkflow(@Param("workflow") Workflow workflow);
}
