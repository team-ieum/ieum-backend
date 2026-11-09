package com.ieum.workflowcore.domain;

import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 기다리기 큐에서 한 실행이 받은 차례 1개. 실행이 끝나면(FAILED 등) 지우지 않아도 대기 수에서 저절로 빠진다 —
 * 세는 쪽이 {@code workflow_runs} 상태를 조인해 본다. {@code executionId}·{@code queueId}는 FK 없는 uuid다.
 */
@Entity
@Table(
    name = "delay_queue_slots",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_delay_queue_slots_queue_execution", columnNames = {"queue_id", "execution_id"}),
    indexes = @Index(name = "idx_delay_queue_slots_queue_release", columnList = "queue_id, release_at")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DelayQueueSlot extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "queue_id", columnDefinition = "uuid", nullable = false)
    private UUID queueId;

    @Column(name = "execution_id", columnDefinition = "uuid", nullable = false)
    private UUID executionId;

    @Column(name = "release_at", nullable = false)
    private LocalDateTime releaseAt;

    public static DelayQueueSlot of(UUID queueId, UUID executionId, LocalDateTime releaseAt) {
        DelayQueueSlot slot = new DelayQueueSlot();
        slot.queueId = queueId;
        slot.executionId = executionId;
        slot.releaseAt = releaseAt;
        return slot;
    }
}
