package com.ieum.workflowcore.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        @Index(name = "idx_workflow_runs_trace_id", columnList = "trace_id"),
        @Index(name = "idx_workflow_runs_retried_by", columnList = "retried_by_execution_id")
    }
)
@Slf4j
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkflowExecution extends BaseEntity {

    /** {@link #waitingApprovalNodeIdList()} 전용. static이라 JPA 매핑·Lombok 게터 대상이 아니다 */
    private static final ObjectMapper JSON = new ObjectMapper();

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
     * 실행 단위 실패 사유 요약. 노드 로그({@code node_runs.error_message})가 남지 않은 실패에서
     * 유일한 원인 기록이다 — 런타임 진입 전 실패(잡 페이로드 해석·trigger_data 복호 실패)와
     * 프로세스 이상 종료로 고립된 실행을 sweeper가 확정한 경우가 그렇다.
     * 노드가 특정된 실패는 여기가 아니라 노드 로그에 원인이 남으므로 null이다.
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

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

    /**
     * 이 실행을 재처리하기 위해 새로 만들어진 실행의 ID. 재처리된 적이 없으면 null.
     *
     * <p>링크 방향이 원본 → 재처리 하나뿐이라 재처리 실행이 자기 원본을 찾을 때는 역방향 조회
     * ({@code findByRetriedByExecutionId})를 쓴다. 이 조회로 재처리 실행이 "원본에서 이미 성공한
     * 노드"를 알아내 건너뛴다 — 잡 큐 페이로드가 executionId뿐이라 스킵 정보도 DB에만 있어야 한다.
     */
    @Column(name = "retried_by_execution_id", columnDefinition = "uuid")
    private UUID retriedByExecutionId;

    /**
     * 승인 대기 중인 게이트 노드 ID 목록(JSON 배열 문자열). 런타임이 멈출 때 조건부 UPDATE
     * ({@code pauseForApproval})로만 쓴다. 승인 시 어느 게이트를 SUCCESS로 적을지의 근거라,
     * 그래프에서 역산하지 않고 멈춘 순간의 실제 집합을 저장한다 — 역산하면 CONDITION 비활성 분기의
     * 게이트까지 대기로 오판한다. 승인·거부 뒤에도 지우지 않는다(이력) — "대기 중"은
     * {@code status = WAITING_APPROVAL}일 때만의 의미다.
     */
    @Column(name = "waiting_approval_node_ids", columnDefinition = "TEXT")
    private String waitingApprovalNodeIds;

    /** 승인 기한. 지나면 sweeper가 {@code FAILED("승인 만료")}로 확정한다 */
    @Column(name = "approval_deadline")
    private LocalDateTime approvalDeadline;

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

    /** 실행 단위 실패 사유를 남긴다. 상태 전이와 분리해 둔 이유는 노드가 특정된 실패엔 쓰지 않기 때문이다. */
    public void recordError(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /** 이 실행의 재처리로 만들어진 새 실행을 연결한다. 재처리를 다시 재처리하면 최신 것으로 덮어쓴다. */
    public void markRetriedBy(UUID retryExecutionId) {
        this.retriedByExecutionId = retryExecutionId;
    }

    /**
     * {@link #waitingApprovalNodeIds}를 목록으로 읽는다. 값이 없으면 빈 목록.
     *
     * <p>해석에 실패해도 빈 목록이다 — 그러면 승인이 게이트 행을 남기지 못해 이어진 실행이 같은
     * 게이트에서 다시 멈춘다. 게이트를 우회하는 쪽으로는 실패하지 않는다. 컬럼은 런타임이
     * {@code ObjectMapper}로 직렬화한 값만 담는다.
     */
    public List<String> waitingApprovalNodeIdList() {
        if (waitingApprovalNodeIds == null) {
            return List.of();
        }
        try {
            List<String> list = JSON.readValue(waitingApprovalNodeIds, new TypeReference<List<String>>() {});
            return list == null ? List.of() : list;
        } catch (Exception e) {
            // 무음이면 "승인해도 같은 게이트에서 다시 멈추는" 루프의 원인이 안 보인다.
            log.warn("[WorkflowExecution] 대기 게이트 목록 해석 실패 — 빈 목록으로 처리. executionId: {}", id, e);
            return List.of();
        }
    }
}
