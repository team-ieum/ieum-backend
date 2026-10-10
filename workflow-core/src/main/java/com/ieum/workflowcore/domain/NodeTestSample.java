package com.ieum.workflowcore.domain;

import com.ieum.common.entity.BaseEntity;
import com.ieum.workflowcore.domain.enums.SampleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 노드 단일 테스트의 최신 결과 1건(워크플로우·노드당). 다음 노드의 참조식·매핑 해석 근거가 된다.
 *
 * <p>워크플로우 JSON·{@code workflow_runs}·{@code node_runs}에는 기록하지 않는다. 출력은
 * {@code SensitiveDataMasker} 적용 후의 JSON 문자열이다(자격증명 원문 저장 금지) — 노드 입출력을
 * TEXT로 두는 {@code node_runs}와 같은 방식이다.
 */
@Entity
@Table(
    name = "node_test_samples",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_node_test_samples_workflow_node", columnNames = {"workflow_id", "node_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NodeTestSample extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id", nullable = false)
    private Workflow workflow;

    /** 워크플로우 정의 내 노드의 식별자 */
    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SampleStatus status;

    /** 마스킹된 출력 JSON. FAILED면 null */
    @Column(name = "output_json", columnDefinition = "TEXT")
    private String outputJson;

    /** 웹훅 URL 마스킹 후의 오류 메시지. SUCCESS면 null */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "tested_at", nullable = false)
    private LocalDateTime testedAt;

    public static NodeTestSample create(Workflow workflow, String nodeId) {
        NodeTestSample sample = new NodeTestSample();
        sample.workflow = workflow;
        sample.nodeId = nodeId;
        return sample;
    }

    /** 이전 결과를 통째로 덮는다 — 최신 1건만 남는다. */
    public void record(SampleStatus status, String outputJson, String errorMessage, LocalDateTime testedAt) {
        this.status = status;
        this.outputJson = outputJson;
        this.errorMessage = errorMessage;
        this.testedAt = testedAt;
    }
}
