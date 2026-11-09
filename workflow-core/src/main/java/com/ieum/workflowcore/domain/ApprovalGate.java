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
import org.hibernate.annotations.ColumnDefault;

/**
 * 승인 게이트 1개(실행·노드 단위). 런타임이 멈출 때 렌더·마스킹한 화면용 config({@code viewJson})와 기한을 남기고,
 * 결정은 게이트마다 따로 받는다. {@code decision}은 문자열이다 — {@code @Enumerated}면 Hibernate가 CHECK 제약을 만든다.
 */
@Entity
@Table(
    name = "approval_gates",
    uniqueConstraints = @UniqueConstraint(name = "uk_approval_gates_execution_node",
        columnNames = {"execution_id", "node_id"}),
    indexes = @Index(name = "idx_approval_gates_user_created", columnList = "user_id, created_at")
    // 미결정 부분 인덱스(idx_approval_gates_open, WHERE decision IS NULL)는 V16 DDL에만 둔다 — @Index는 부분 조건을
    // 표현하지 못하고, validate는 인덱스를 보지 않아 엔티티에 없어도 기동한다.
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApprovalGate extends BaseEntity {

    public static final int TITLE_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "execution_id", columnDefinition = "uuid", nullable = false)
    private UUID executionId;

    @Column(name = "workflow_id", columnDefinition = "uuid", nullable = false)
    private UUID workflowId;

    @Column(name = "user_id", columnDefinition = "uuid", nullable = false)
    private UUID userId;

    @Column(name = "node_id", nullable = false, length = 100)
    private String nodeId;

    @Column(name = "event", nullable = false, length = 100)
    private String event;

    @Column(name = "view_json", columnDefinition = "TEXT", nullable = false)
    private String viewJson;

    /** 목록 제목 — 렌더·마스킹한 message 앞 200자. 목록이 view_json을 읽지 않게 따로 둔다. */
    @ColumnDefault("''")
    @Column(name = "title", nullable = false, length = TITLE_LENGTH)
    private String title;

    @Column(name = "webhook_id", columnDefinition = "uuid")
    private UUID webhookId;

    @Column(name = "deadline", nullable = false)
    private LocalDateTime deadline;

    @Column(name = "remind_at")
    private LocalDateTime remindAt;

    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    @Column(name = "reminded_at")
    private LocalDateTime remindedAt;

    @Column(name = "decision", length = 20)
    private String decision;

    @Column(name = "output_json", columnDefinition = "TEXT")
    private String outputJson;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** {@code title}은 앞 {@value #TITLE_LENGTH}자로 자른다(null은 빈 문자열). */
    public static ApprovalGate open(UUID executionId, UUID workflowId, UUID userId, String nodeId, String event,
                                    String viewJson, String title, UUID webhookId, LocalDateTime deadline,
                                    LocalDateTime remindAt) {
        ApprovalGate g = new ApprovalGate();
        g.executionId = executionId;
        g.workflowId = workflowId;
        g.userId = userId;
        g.nodeId = nodeId;
        g.event = event;
        g.viewJson = viewJson;
        String t = title == null ? "" : title;
        g.title = t.length() > TITLE_LENGTH ? t.substring(0, TITLE_LENGTH) : t;
        g.webhookId = webhookId;
        g.deadline = deadline;
        g.remindAt = remindAt;
        return g;
    }

    public void decide(String decision, String outputJson, LocalDateTime at) {
        this.decision = decision;
        this.outputJson = outputJson;
        this.decidedAt = at;
    }

    public boolean isDecided() {
        return decision != null;
    }
}
