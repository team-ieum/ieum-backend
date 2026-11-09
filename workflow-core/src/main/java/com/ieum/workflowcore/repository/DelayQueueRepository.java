package com.ieum.workflowcore.repository;

import com.ieum.workflowcore.domain.DelayQueue;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DelayQueueRepository extends JpaRepository<DelayQueue, UUID> {

    /**
     * 큐 행이 없을 때만 만든다. 충돌 대상 열을 쓰지 않는다 — 테스트 H2(PostgreSQL 모드)는 대상 없는 형식만 읽는다.
     * id는 새 UUID라 걸리는 제약은 {@code uk_delay_queues_user_name}뿐이다. bulk INSERT라 감사 시각을 직접 넣는다.
     */
    @Modifying
    @Query(value = "INSERT INTO delay_queues (id, user_id, name, created_at, updated_at) "
        + "VALUES (:id, :userId, :name, :now, :now) ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId, @Param("name") String name,
                       @Param("now") LocalDateTime now);

    /** 예약 직렬화 — 같은 큐에 동시에 들어온 실행이 같은 슬롯을 받지 않게 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM DelayQueue q WHERE q.userId = :userId AND q.name = :name")
    Optional<DelayQueue> findForUpdate(@Param("userId") UUID userId, @Param("name") String name);

    Optional<DelayQueue> findByUserIdAndName(UUID userId, String name);

    /** 회원 탈퇴 — 그 사용자의 큐 행을 지운다(슬롯을 먼저 지울 것). */
    @Modifying
    @Query("DELETE FROM DelayQueue q WHERE q.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
