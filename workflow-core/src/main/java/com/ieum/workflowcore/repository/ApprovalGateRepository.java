package com.ieum.workflowcore.repository;

import com.ieum.workflowcore.domain.ApprovalGate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 게이트. 대기 판정은 게이트가 아니라 실행 상태({@code WAITING_APPROVAL})로 한다 — 거절(멈춤)로 닫힌 실행의
 * 나머지 게이트는 결정 없이 남기 때문이다. 알림 선점({@code markNotified}·{@code markReminded})은 sweeper가
 * 트랜잭션 밖에서 부르므로 {@code @Transactional}이 필수다.
 */
public interface ApprovalGateRepository extends JpaRepository<ApprovalGate, UUID> {

    List<ApprovalGate> findByExecutionIdOrderByCreatedAtAsc(UUID executionId);

    Optional<ApprovalGate> findByExecutionIdAndNodeId(UUID executionId, String nodeId);

    /** 대기 목록 한 행 — {@code view_json}(최대 수백 KB)을 읽지 않게 필요한 칸만 투영한다. */
    interface OpenGate {
        UUID getId();

        UUID getExecutionId();

        UUID getWorkflowId();

        String getNodeId();

        String getEvent();

        String getTitle();

        LocalDateTime getDeadline();

        LocalDateTime getCreatedAt();
    }

    /** 내 대기 목록 — 미결정이고 실행이 승인 대기인 게이트, 최신순. 페이지는 호출자가 {@code size + 1}로 hasNext를 본다. */
    @Query("SELECT g.id AS id, g.executionId AS executionId, g.workflowId AS workflowId, g.nodeId AS nodeId, "
        + "g.event AS event, g.title AS title, g.deadline AS deadline, g.createdAt AS createdAt "
        + "FROM ApprovalGate g JOIN WorkflowExecution e ON e.id = g.executionId "
        + "WHERE g.userId = :userId AND g.decision IS NULL "
        + "AND e.status = com.ieum.workflowcore.domain.enums.ExecutionStatus.WAITING_APPROVAL "
        + "ORDER BY g.createdAt DESC, g.id DESC")
    List<OpenGate> findOpenByUser(@Param("userId") UUID userId, Pageable page);

    /** 첫 알림 대상 — 웹훅이 있고 아직 안 보낸 미결정 게이트. */
    @Query("SELECT g FROM ApprovalGate g JOIN WorkflowExecution e ON e.id = g.executionId "
        + "WHERE g.webhookId IS NOT NULL AND g.notifiedAt IS NULL AND g.decision IS NULL "
        + "AND e.status = com.ieum.workflowcore.domain.enums.ExecutionStatus.WAITING_APPROVAL "
        + "AND g.createdAt < :before ORDER BY g.createdAt, g.id")
    List<ApprovalGate> findUnnotified(@Param("before") LocalDateTime before, Pageable page);

    /** 리마인더 대상 — 리마인더 시각이 지났고 아직 안 보낸 미결정 게이트. */
    @Query("SELECT g FROM ApprovalGate g JOIN WorkflowExecution e ON e.id = g.executionId "
        + "WHERE g.webhookId IS NOT NULL AND g.remindAt <= :now AND g.remindedAt IS NULL AND g.decision IS NULL "
        + "AND e.status = com.ieum.workflowcore.domain.enums.ExecutionStatus.WAITING_APPROVAL "
        + "ORDER BY g.remindAt, g.id")
    List<ApprovalGate> findDueReminders(@Param("now") LocalDateTime now, Pageable page);

    /**
     * 첫 알림 선점 — 아직 안 보냈고 미결정일 때만 1(조회 뒤 막 결정된 게이트에 요청 알림이 가지 않게).
     * bulk UPDATE라 {@code updatedAt}을 직접 쓴다.
     */
    @Transactional
    @Modifying
    @Query("UPDATE ApprovalGate g SET g.notifiedAt = :now, g.updatedAt = :now "
        + "WHERE g.id = :id AND g.notifiedAt IS NULL AND g.decision IS NULL")
    int markNotified(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** 리마인더 선점 — 아직 안 보냈고 미결정일 때만 1. */
    @Transactional
    @Modifying
    @Query("UPDATE ApprovalGate g SET g.remindedAt = :now, g.updatedAt = :now "
        + "WHERE g.id = :id AND g.remindedAt IS NULL AND g.decision IS NULL")
    int markReminded(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** 첫 알림 선점 되돌리기 — 발송이 일시 실패했을 때, {@code claimedAt}으로 선점한 행만 다음 스윕에 다시 잡히게 한다. */
    @Transactional
    @Modifying
    @Query("UPDATE ApprovalGate g SET g.notifiedAt = NULL, g.updatedAt = :claimedAt "
        + "WHERE g.id = :id AND g.notifiedAt = :claimedAt")
    int unmarkNotified(@Param("id") UUID id, @Param("claimedAt") LocalDateTime claimedAt);

    /** 리마인더 선점 되돌리기 — {@link #unmarkNotified}와 같다. */
    @Transactional
    @Modifying
    @Query("UPDATE ApprovalGate g SET g.remindedAt = NULL, g.updatedAt = :claimedAt "
        + "WHERE g.id = :id AND g.remindedAt = :claimedAt")
    int unmarkReminded(@Param("id") UUID id, @Param("claimedAt") LocalDateTime claimedAt);

    /** 게이트의 실행 ID만 — 엔티티를 영속성 컨텍스트에 올리지 않고 실행 잠금을 먼저 잡기 위해. */
    @Query("SELECT g.executionId FROM ApprovalGate g WHERE g.id = :id")
    Optional<UUID> findExecutionIdById(@Param("id") UUID gateId);

    @Transactional
    @Modifying
    @Query("DELETE FROM ApprovalGate g WHERE g.executionId = :executionId")
    void deleteByExecutionId(@Param("executionId") UUID executionId);

    /** 워크플로우 삭제 — 호출자 트랜잭션 안에서 부른다. */
    @Transactional
    @Modifying
    @Query("DELETE FROM ApprovalGate g WHERE g.workflowId = :workflowId")
    void deleteByWorkflowId(@Param("workflowId") UUID workflowId);

    /** 회원 탈퇴. */
    @Transactional
    @Modifying
    @Query("DELETE FROM ApprovalGate g WHERE g.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
