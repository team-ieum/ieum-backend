package com.ieum.workflowcore.domain;

import com.ieum.common.entity.BaseEntity;
import com.ieum.workflowcore.domain.enums.TriggerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 워크플로우 정의의 메타데이터를 저장하는 엔티티.
 * 실제 노드/엣지 구성은 WorkflowVersion에서 버전별로 관리된다.
 */
@Entity
@Table(
    name = "workflows",
    indexes = {
        @Index(name = "idx_workflows_user_id", columnList = "user_id"),
        @Index(name = "idx_workflows_is_active", columnList = "is_active")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Workflow extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    /** 워크플로우 소유자 */
    @Column(name = "user_id", columnDefinition = "uuid", nullable = false)
    private UUID userId;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    /** 워크플로우 활성화 여부 (비활성 시 트리거 무시) */
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    /** 트리거 타입 (MANUAL / WEBHOOK / SCHEDULE) */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20,
            columnDefinition = "VARCHAR(20) DEFAULT 'MANUAL'")
    private TriggerType triggerType = TriggerType.MANUAL;

    /** Quartz Cron 표현식 — SCHEDULE 트리거일 때만 사용 (nullable) */
    @Column(name = "cron_expression", length = 100)
    private String cronExpression;

    @Builder
    private Workflow(UUID userId, String name, String description, boolean isActive,
            TriggerType triggerType, String cronExpression) {
        this.userId = userId;
        this.name = name;
        this.description = description;
        this.isActive = isActive;
        this.triggerType = triggerType != null ? triggerType : TriggerType.MANUAL;
        this.cronExpression = cronExpression;
    }

    public void activate() {
        this.isActive = true;
    }

    public void deactivate() {
        this.isActive = false;
    }

    public void update(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public void updateSchedule(TriggerType triggerType, String cronExpression) {
        this.triggerType = triggerType != null ? triggerType : TriggerType.MANUAL;
        this.cronExpression = cronExpression;
    }

    public void updateName(String name) {
        this.name = name;
    }
}
