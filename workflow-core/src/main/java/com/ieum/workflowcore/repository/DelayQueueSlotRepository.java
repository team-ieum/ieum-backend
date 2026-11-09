package com.ieum.workflowcore.repository;

import com.ieum.workflowcore.domain.DelayQueueSlot;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DelayQueueSlotRepository extends JpaRepository<DelayQueueSlot, UUID> {

    Optional<DelayQueueSlot> findByQueueIdAndExecutionId(UUID queueId, UUID executionId);

    /**
     * 간격의 기준 — 실패·취소되지 않은 실행의 가장 늦은 차례. 이미 지나간(재개된) 차례도 넣는다:
     * 방금 나간 실행 뒤에도 간격을 지켜야 한다. FAILED 실행의 차례는 비운 것으로 본다.
     */
    @Query("SELECT MAX(s.releaseAt) FROM DelayQueueSlot s, WorkflowExecution e "
        + "WHERE e.id = s.executionId AND s.queueId = :queueId "
        + "AND e.status <> com.ieum.workflowcore.domain.enums.ExecutionStatus.FAILED")
    LocalDateTime findLastRelease(@Param("queueId") UUID queueId);

    /** {@code now}와 {@code before} 사이에 아직 나가지 않은 차례 중, 실행이 살아 있는(대기·실행 중) 것의 수. */
    @Query("SELECT COUNT(s) FROM DelayQueueSlot s, WorkflowExecution e "
        + "WHERE e.id = s.executionId AND s.queueId = :queueId "
        + "AND s.releaseAt > :now AND s.releaseAt < :before "
        + "AND e.status IN (com.ieum.workflowcore.domain.enums.ExecutionStatus.RUNNING, "
        + "com.ieum.workflowcore.domain.enums.ExecutionStatus.WAITING_DELAY, "
        + "com.ieum.workflowcore.domain.enums.ExecutionStatus.WAITING_APPROVAL)")
    long countWaiting(@Param("queueId") UUID queueId, @Param("now") LocalDateTime now,
                      @Param("before") LocalDateTime before);

    /** 회원 탈퇴 — 그 사용자 큐들의 슬롯을 지운다. 큐 행보다 먼저 부른다. */
    @Modifying
    @Query("DELETE FROM DelayQueueSlot s WHERE s.queueId IN (SELECT q.id FROM DelayQueue q WHERE q.userId = :userId)")
    void deleteByUserId(@Param("userId") UUID userId);

    /** {@code cutoff}보다 이른 차례를 최대 {@code limit}개 지운다. 지운 수를 돌려준다(호출자가 0이 될 때까지 반복). */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM delay_queue_slots WHERE id IN "
        + "(SELECT id FROM delay_queue_slots WHERE release_at < :cutoff LIMIT :limit)", nativeQuery = true)
    int deleteReleasedBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
