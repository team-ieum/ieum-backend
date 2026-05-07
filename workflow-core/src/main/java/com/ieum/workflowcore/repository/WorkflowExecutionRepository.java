package com.ieum.workflowcore.repository;

import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, UUID> {

    List<WorkflowExecution> findByWorkflowId(UUID workflowId, Pageable pageable);

    List<WorkflowExecution> findByWorkflowIdAndStatus(UUID workflowId, ExecutionStatus status, Pageable pageable);

    List<WorkflowExecution> findByWorkflowIdAndTriggerType(UUID workflowId, TriggerType triggerType, Pageable pageable);
}
