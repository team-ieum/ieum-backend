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
import org.hibernate.annotations.ColumnDefault;

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

    /** 실패 원인이 재시도 대상이었고 재시도를 모두 소진한 뒤에도 실패했는지. 판정은 런타임이 하고 여기엔 결과만 저장한다 */
    @ColumnDefault("false")
    @Column(name = "retry_exhausted", nullable = false)
    private boolean retryExhausted;

    /**
     * 트리거가 전달한 초기 입력. {@code com.ieum.common.util.AesEncryptor}로 암호화된 JSON
     * 문자열이다 — 평문이 아니다, 직접 파싱하지 말 것. 복호는 {@code AesEncryptor.decrypt()} 후
     * JSON 역직렬화(실패 실행 재처리, Task 10에서 사용). 복호 실패 시 {@code AesEncryptor}가
     * {@code CustomException(CREDENTIAL_DECRYPT_FAILED)}를 던진다. null은 "트리거 입력이 없었다"만
     * 뜻한다 — 암호화 실패는 {@code prepareExecution}이 fail-fast로 막으므로 유실로 인한 null은 없다.
     * 조회 API 응답에 그대로 노출하지 말 것 — 노출이 필요해지면 "복호 → 마스킹 → 노출" 순서를 지킬 것.
     */
    @Column(name = "trigger_data", columnDefinition = "TEXT")
    private String triggerData;

    @Builder
    private WorkflowExecution(
        Workflow workflow,
        WorkflowVersion workflowVersion,
        ExecutionStatus status,
        TriggerType triggerType,
        LocalDateTime startedAt,
        String traceId,
        String triggerData
    ) {
        this.workflow = workflow;
        this.workflowVersion = workflowVersion;
        this.status = status;
        this.triggerType = triggerType;
        this.startedAt = startedAt;
        this.traceId = traceId;
        this.triggerData = triggerData;
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
        fail(false);
    }

    /** @param retryExhausted 재시도 대상 실패로 재시도를 모두 소진하고도 실패했는지 (판정은 호출부 책임) */
    public void fail(boolean retryExhausted) {
        this.status = ExecutionStatus.FAILED;
        this.finishedAt = LocalDateTime.now();
        this.retryExhausted = retryExhausted;
    }
}
