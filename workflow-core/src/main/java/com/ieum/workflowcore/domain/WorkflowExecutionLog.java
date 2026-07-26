package com.ieum.workflowcore.domain;

import com.ieum.common.entity.BaseEntity;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.NodeType;
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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 워크플로우 실행 중 각 노드의 실행 결과를 저장하는 엔티티.
 * input/output JSON에 자격증명(토큰, API Key) 원문 저장 절대 금지 — 저장 전 필터링 필수.
 */
@Entity
@Table(
    name = "node_runs",
    indexes = {
        @Index(name = "idx_node_runs_execution_id", columnList = "execution_id"),
        @Index(name = "idx_node_runs_node_id", columnList = "node_id")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkflowExecutionLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private WorkflowExecution execution;

    /** 워크플로우 정의 내 노드의 식별자 */
    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false)
    private NodeType nodeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExecutionLogStatus status;

    /** 노드 실행 입력값 JSON — 자격증명 원문 포함 금지 */
    @Column(name = "input_json", columnDefinition = "TEXT")
    private String inputJson;

    /** 노드 실행 출력값 JSON — 자격증명 원문 포함 금지 */
    @Column(name = "output_json", columnDefinition = "TEXT")
    private String outputJson;

    @Column(name = "error_message")
    private String errorMessage;

    /** 노드 실행 소요 시간 (밀리초) */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** 이 노드가 속한 실행의 traceId. 노드 로그 한 줄에서 바로 Phoenix로 찾아갈 수 있게 중복 저장한다. */
    @Column(name = "trace_id", length = 32)
    private String traceId;

    /** LLM 입력 토큰 수. AI 노드만 채워진다 */
    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    /** LLM 출력 토큰 수. AI 노드만 채워진다 */
    @Column(name = "completion_tokens")
    private Integer completionTokens;

    /** LLM 총 토큰 수. AI 노드만 채워진다 */
    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Builder
    private WorkflowExecutionLog(
        WorkflowExecution execution,
        String nodeId,
        NodeType nodeType,
        ExecutionLogStatus status,
        String inputJson,
        String outputJson,
        String errorMessage,
        Long durationMs,
        String traceId,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
    ) {
        this.execution = execution;
        this.nodeId = nodeId;
        this.nodeType = nodeType;
        this.status = status;
        this.inputJson = inputJson;
        this.outputJson = outputJson;
        this.errorMessage = errorMessage;
        this.durationMs = durationMs;
        this.traceId = traceId;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }
}
