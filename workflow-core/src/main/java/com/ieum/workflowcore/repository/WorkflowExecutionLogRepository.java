package com.ieum.workflowcore.repository;

import com.ieum.workflowcore.domain.WorkflowExecutionLog;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowExecutionLogRepository extends JpaRepository<WorkflowExecutionLog, UUID> {

    List<WorkflowExecutionLog> findByExecutionIdOrderByCreatedAtAsc(UUID executionId);

    long countByExecutionIdAndStatus(UUID executionId, ExecutionLogStatus status);
}
