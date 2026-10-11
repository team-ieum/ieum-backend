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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 활성 앱 트리거 1건(워크플로우·트리거 노드당). 행이 있다 = 그 트리거가 켜져 있다 — 비활성·삭제·다른 트리거로 바뀌면
 * {@code AppTriggerSubscriptionService.sync}가 지운다. 상태가 DB에 있어 재기동 복구 코드가 없다.
 *
 * <p>{@code workflowId}는 FK 없는 uuid다 — 스윕러가 커밋 뒤 detached 상태로 읽으므로 지연 로딩 연관을 두지 않는다.
 * 워크플로우 삭제 시 {@code WorkflowCrudService.deleteWorkflowInternal}이 명시적으로 지운다.
 */
@Entity
@Table(
    name = "app_trigger_subscriptions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_app_trigger_subscriptions_workflow_node", columnNames = {"workflow_id", "node_id"}),
    indexes = {
        @Index(name = "idx_app_trigger_subscriptions_type_resource", columnList = "trigger_type, resource_key"),
        @Index(name = "idx_app_trigger_subscriptions_next_poll", columnList = "next_poll_at")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppTriggerSubscription extends BaseEntity {

    public static final int LAST_ERROR_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "workflow_id", columnDefinition = "uuid", nullable = false)
    private UUID workflowId;

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 30)
    private TriggerType triggerType;

    /** 워크플로우 소유자 — 토큰 조회 키 */
    @Column(name = "user_id", columnDefinition = "uuid", nullable = false)
    private UUID userId;

    /** GitHub: repoId 십진 문자열. Gmail: null */
    @Column(name = "resource_key", length = 100)
    private String resourceKey;

    /** 트리거 config 스냅샷 — Gmail {@code {query?}}, GitHub {@code {repoId}} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false)
    private Map<String, Object> config;

    /** Gmail {@code {afterSec, floorSec, seen{id: sec}}}(api GmailCursor). GitHub null. 칼럼명은 SQL 키워드 CURSOR를 피한다 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "poll_cursor")
    private Map<String, Object> cursor;

    /** Gmail만. 클레임 시 리스로 미래 시각이 된다 */
    @Column(name = "next_poll_at")
    private LocalDateTime nextPollAt;

    @Column(name = "last_error", length = LAST_ERROR_MAX)
    private String lastError;

    @Column(name = "last_polled_at")
    private LocalDateTime lastPolledAt;

    public static AppTriggerSubscription create(UUID workflowId, String nodeId, UUID userId, TriggerType type,
                                                String resourceKey, Map<String, Object> config, LocalDateTime now) {
        AppTriggerSubscription s = new AppTriggerSubscription();
        s.workflowId = workflowId;
        s.nodeId = nodeId;
        s.userId = userId;
        s.triggerType = type;
        s.resourceKey = resourceKey;
        s.config = config;
        s.resetPolling(now);
        return s;
    }

    /**
     * 저장·활성화 때의 재구성. triggerType이나 {@code query}가 바뀌었을 때만 폴링 상태를 리셋한다 — 리셋은 "지금 이후 메일만"이라
     * 매 저장마다 리셋하면 마지막 폴링~저장 사이 메일이 유실되고, 바뀐 query를 옛 커서로 돌리면 과거 메일이 한꺼번에 발화한다.
     */
    public void reconfigure(TriggerType type, UUID userId, String resourceKey, Map<String, Object> config,
                            LocalDateTime now) {
        boolean changed = type != this.triggerType
            || !Objects.equals(this.config.get("query"), config.get("query"));
        this.triggerType = type;
        this.userId = userId;
        this.resourceKey = resourceKey;
        this.config = config;
        if (changed) {
            resetPolling(now);
        }
    }

    /** 클레임 리스 — 처리 중 다른 스윕이 다시 집지 않게 다음 폴링 시각을 미리 민다. */
    public void lease(LocalDateTime until) {
        this.nextPollAt = until;
    }

    public void recordPoll(Map<String, Object> cursor, String error, LocalDateTime polledAt, LocalDateTime nextPollAt) {
        this.cursor = cursor;
        this.lastError = error == null || error.length() <= LAST_ERROR_MAX ? error : error.substring(0, LAST_ERROR_MAX);
        this.lastPolledAt = polledAt;
        this.nextPollAt = nextPollAt;
    }

    private void resetPolling(LocalDateTime now) {
        this.lastError = null;
        if (triggerType == TriggerType.GMAIL_NEW_EMAIL) {
            // 나머지 키(floorSec·seen)는 api GmailCursor가 채운다 — floorSec가 없으면 afterSec(=지금)가 하한이다
            this.cursor = Map.of("afterSec", now.atZone(ZoneId.systemDefault()).toEpochSecond());
            this.nextPollAt = now;
        } else {
            this.cursor = null;
            this.nextPollAt = null;
        }
    }
}
