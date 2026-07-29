package com.ieum.workflowcore.repository;

import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowExecutionLog;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowExecutionLogRepository extends JpaRepository<WorkflowExecutionLog, UUID> {

    List<WorkflowExecutionLog> findByExecutionIdOrderByCreatedAtAsc(UUID executionId);

    long countByExecutionIdAndStatus(UUID executionId, ExecutionLogStatus status);

    List<WorkflowExecutionLog> findByExecutionIdAndStatusIn(
        UUID executionId, Collection<ExecutionLogStatus> statuses);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM WorkflowExecutionLog wl WHERE wl.execution = :execution")
    void deleteByExecution(@Param("execution") WorkflowExecution execution);

    /**
     * 여러 실행 ID에 속한 로그를 단일 쿼리로 삭제한다 (N+1 방지).
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM WorkflowExecutionLog wl WHERE wl.execution.id IN :executionIds")
    void deleteByExecutionIdIn(@Param("executionIds") List<UUID> executionIds);
}
