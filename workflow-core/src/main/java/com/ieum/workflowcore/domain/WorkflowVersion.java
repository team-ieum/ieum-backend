package com.ieum.workflowcore.domain;

import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 워크플로우의 버전별 노드/엣지 구성을 저장하는 엔티티.
 * 실행 시 어떤 버전으로 실행되었는지 추적할 수 있도록 WorkflowExecution이 이를 참조한다.
 */
@Entity
@Table(
    name = "workflow_versions",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_workflow_versions_workflow_version", columnNames = {"workflow_id", "version"})
    },
    indexes = {
        @Index(name = "idx_workflow_versions_workflow_id", columnList = "workflow_id")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkflowVersion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id", nullable = false)
    private Workflow workflow;

    /** 버전 번호 (1부터 단조 증가) */
    @Column(name = "version", nullable = false)
    private int version;

    /** MongoDB ObjectId referencing the WorkflowDefinitionDocument */
    @Column(name = "mongo_definition_id", length = 24, nullable = false)
    private String mongoDefinitionId;

    @Builder
    private WorkflowVersion(Workflow workflow, int version, String mongoDefinitionId) {
        this.workflow = workflow;
        this.version = version;
        this.mongoDefinitionId = mongoDefinitionId;
    }
}
