package com.ieum.workflowcore.repository;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    List<Workflow> findByUserId(UUID userId, Pageable pageable);

    List<Workflow> findByUserIdAndIsActive(UUID userId, boolean isActive, Pageable pageable);

    Optional<Workflow> findByIdAndUserId(UUID id, UUID userId);

    /** 서버 재시작 시 RAMJobStore 복구용 — 활성 상태인 SCHEDULE 트리거 워크플로우 전체 조회 */
    List<Workflow> findByTriggerTypeAndIsActive(TriggerType triggerType, boolean isActive);
}
