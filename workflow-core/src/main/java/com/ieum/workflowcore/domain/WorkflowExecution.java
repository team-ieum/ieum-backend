package com.ieum.workflowcore.domain;

import com.ieum.common.entity.BaseEntity;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 워크플로우의 각 실행 인스턴스를 기록하는 엔티티.
 * 실행 시점의 버전(workflowVersion)을 고정 참조하여 버전 변경에 영향받지 않는다.
 */
@Entity
@Table(
    name = "workflow_runs",
    indexes = {
        @Index(name = "idx_workflow_runs_workflow_id", columnList = "workflow_id"),
        @Index(name = "idx_workflow_runs_status", columnList = "status"),
        @Index(name = "idx_workflow_runs_trigger_type", columnList = "trigger_type"),
        @Index(name = "idx_workflow_runs_trace_id", columnList = "trace_id")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkflowExecution extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id", nullable = false)
    private Workflow workflow;

    /** 실행 시점에 고정된 워크플로우 버전 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_version_id", nullable = false)
    private WorkflowVersion workflowVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExecutionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private TriggerType triggerType;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** Phoenix span 속성 ieum.trace_id와 조인하는 실행 단위 상관관계 ID(32자 무하이픈 hex) */
    @Column(name = "trace_id", length = 32)
    private String traceId;

    @Builder
    private WorkflowExecution(
        Workflow workflow,
        WorkflowVersion workflowVersion,
        ExecutionStatus status,
        TriggerType triggerType,
        LocalDateTime startedAt,
        String traceId
    ) {
        this.workflow = workflow;
        this.workflowVersion = workflowVersion;
        this.status = status;
        this.triggerType = triggerType;
        this.startedAt = startedAt;
        this.traceId = traceId;
    }

    public void start() {
        this.status = ExecutionStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
    }

    public void complete() {
        this.status = ExecutionStatus.SUCCESS;
        this.finishedAt = LocalDateTime.now();
    }

    public void fail() {
        this.status = ExecutionStatus.FAILED;
        this.finishedAt = LocalDateTime.now();
    }
}
