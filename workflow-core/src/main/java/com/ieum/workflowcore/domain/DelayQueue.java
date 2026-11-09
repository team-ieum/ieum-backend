package com.ieum.workflowcore.domain;

import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 기다리기 큐(사용자·이름 단위) 1개. 차례는 "마지막으로 내보낼 시각 + 간격"으로 정한다 — 행은
 * {@code DelayQueueRepository.insertIfAbsent}로 만들고, 예약은 이 행을 잠가 직렬화한다.
 */
@Entity
@Table(
    name = "delay_queues",
    uniqueConstraints = @UniqueConstraint(name = "uk_delay_queues_user_name", columnNames = {"user_id", "name"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DelayQueue extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", columnDefinition = "uuid", nullable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 이 큐에서 마지막으로 예약한 재개 시각. 아직 예약이 없으면 null */
    @Column(name = "last_release_at")
    private LocalDateTime lastReleaseAt;

    public void release(LocalDateTime at) {
        this.lastReleaseAt = at;
    }
}
