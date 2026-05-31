package com.ieum.workflowcore.repository;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    List<Workflow> findByUserId(UUID userId, Pageable pageable);

    List<Workflow> findByUserIdAndIsActive(UUID userId, boolean isActive, Pageable pageable);

    Optional<Workflow> findByIdAndUserId(UUID id, UUID userId);

    /** 서버 재시작 시 RAMJobStore 복구용 — 활성 상태인 SCHEDULE 트리거 워크플로우 전체 조회 */
    List<Workflow> findByTriggerTypeAndIsActive(TriggerType triggerType, boolean isActive);

    /**
     * 고아 빈 워크플로우 후보 조회.
     * 생성된 지 threshold 이상 지났고, AI가 워크플로우를 아직 생성하지 않은 것(maxVersion <= 1, 버전 0개 포함)만 반환한다.
     * 실제 nodes 비어있는지 여부는 MongoDB에서 2단계로 확인한다.
     */
    @Query("SELECT w.id FROM Workflow w WHERE w.createdAt < :threshold AND " +
           "(SELECT COALESCE(MAX(wv.version), 0) FROM WorkflowVersion wv WHERE wv.workflow.id = w.id) <= 1")
    List<UUID> findOrphanCandidates(@Param("threshold") LocalDateTime threshold);
}
