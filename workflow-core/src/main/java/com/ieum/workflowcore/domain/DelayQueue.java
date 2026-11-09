package com.ieum.workflowcore.domain;

import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 기다리기 큐(사용자·이름 단위) 1개. 차례는 실행별 슬롯({@link DelayQueueSlot})으로 센다 — 이 행은
 * {@code DelayQueueRepository.insertIfAbsent}로 만들고, 예약은 이 행을 잠가 직렬화하는 데만 쓴다.
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
}
