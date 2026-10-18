package com.ieum.workflowcore.service;

import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.domain.AppTriggerSubscription;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.repository.AppTriggerSubscriptionRepository;
import com.ieum.workflowcore.trigger.PollStart;
import com.ieum.workflowcore.trigger.TriggerHandler;
import com.ieum.workflowcore.trigger.TriggerHandlerRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱 트리거 구독 행의 동기화·클레임·폴링 기록. 종류별 규칙(스냅샷·resource_key·접근 검증·폴링 시작)은 {@link TriggerHandler}가
 * 정하고, 외부 호출·실행 생성은 api 모듈 몫이다. 여기는 행만 다룬다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AppTriggerSubscriptionService {

    private final AppTriggerSubscriptionRepository repository;
    private final TriggerHandlerRegistry triggerHandlers;
    private final EventCatalog eventCatalog;

    /** 이 종류가 구독 행으로 켜지는가 — {@code WorkflowCrudService.activateWorkflow}가 Mongo 정의를 읽을지 정한다. null이면 false */
    public boolean subscribes(TriggerType type) {
        return type != null && triggerHandlers.get(type).subscribes();
    }

    /**
     * 저장될 정의·활성 상태에 구독 행을 맞춘다. {@code WorkflowCrudService}의 생성·수정·AI 저장·활성화·비활성화가
     * 같은 트랜잭션 안에서 부른다(Quartz와 달리 afterCommit이 아니다 — 같은 PG라 원자적으로 묶고, 검증 거부를 400으로 낸다).
     *
     * <p>첫 TRIGGER 노드(저장 계산 {@code TriggerNodeSchedule}·실행 {@code SyncExecutionRuntime}·{@code includeRaw}와 같은 선택)의
     * {@code event}가 카탈로그에서 구독형 트리거({@link TriggerHandler#subscribes()})이고 워크플로우가 활성이면 그 노드 id로 upsert,
     * 아니면 그 워크플로우의 행을 전부 지운다. 같은 노드 id면 행을 재사용해 재구성한다 — 지우고 같은 (workflow_id, node_id)로 다시
     * 넣으면 Hibernate flush 순서(insert가 delete보다 먼저)로 UNIQUE 위반이다.
     *
     * @param nodes 저장될 노드 Map 목록. 비활성화처럼 정의가 무관하면 빈 목록
     */
    @Transactional
    public void sync(Workflow workflow, List<Map<String, Object>> nodes) {
        AppTrigger trigger = workflow.isActive() ? findAppTrigger(nodes) : null;
        AppTriggerSubscription keep = null;
        for (AppTriggerSubscription row : repository.findByWorkflowId(workflow.getId())) {
            if (trigger != null && row.getNodeId().equals(trigger.nodeId())) {
                keep = row;
            } else {
                repository.delete(row);
            }
        }
        if (trigger == null) {
            return;
        }
        TriggerHandler handler = triggerHandlers.get(trigger.type());
        Map<String, Object> snapshot = handler.snapshot(trigger.config());
        String resourceKey = handler.resourceKey(snapshot);
        // 같은 행·같은 리소스면 다시 묻지 않는다 — GitHub 장애(503)가 무관한 편집·AI 저장을 막지 않게.
        // 비활성화가 행을 지우므로 생성·활성화는 늘 새 행 → 늘 검증된다. 접근 회수는 BE-81 수신 시점 재검증이 막는다.
        boolean unchanged = keep != null && keep.getTriggerType() == trigger.type()
            && Objects.equals(keep.getResourceKey(), resourceKey);
        if (!unchanged) {
            handler.verifyAccess(workflow.getUserId(), snapshot);
        }
        PollStart start = handler.pollStart(LocalDateTime.now());
        if (keep != null) {
            keep.reconfigure(trigger.type(), workflow.getUserId(), resourceKey, snapshot, start);
        } else {
            repository.save(AppTriggerSubscription.create(workflow.getId(), trigger.nodeId(), workflow.getUserId(),
                trigger.type(), resourceKey, snapshot, start));
        }
    }

    /**
     * 기한이 지난 행을 잠가 집고(SKIP LOCKED) {@code now + lease}로 리스를 건 뒤 커밋한다. 처리(외부 호출·실행 생성)는
     * 호출자가 트랜잭션 밖에서 한다 — 실행 생성이 이 트랜잭션에 합류하면 비동기 워커가 미커밋 행을 읽는다.
     */
    @Transactional
    public List<AppTriggerSubscription> claimDue(TriggerType type, LocalDateTime now, int limit, Duration lease) {
        List<AppTriggerSubscription> due = repository.lockDue(type.name(), now, limit);
        LocalDateTime until = now.plus(lease);
        due.forEach(s -> s.lease(until));
        return due;
    }

    /**
     * 폴링 결과 기록. 행을 잠가 읽는다 — 안 잠그면 읽기~UPDATE 사이 sync가 커밋한 query 변경을 전체 칼럼 UPDATE가 옛 config로
     * 되돌린다. 그 사이 행이 지워졌거나(비활성화·삭제) config가 바뀌었으면(sync가 커서·오류·다음 폴링을 리셋) 아무것도 쓰지 않는다
     * — 옛 query 기준 커서·오류가 바뀐 query에 덮인다. sync와 데드락 없음: 여기는 이 행 하나만 잠근다.
     *
     * @param claimedConfig 클레임 시점 행의 config
     */
    @Transactional
    public void recordPoll(UUID id, Map<String, Object> claimedConfig, Map<String, Object> cursor, String error,
                           LocalDateTime nextPollAt) {
        repository.findForUpdate(id)
            .filter(s -> Objects.equals(s.getConfig(), claimedConfig))
            .ifPresent(s -> s.recordPoll(cursor, error, LocalDateTime.now(), nextPollAt));
    }

    /** BE-81 webhook 라우팅 — {@code (GITHUB_NEW_ISSUE, repoId)}의 활성 구독. */
    public List<AppTriggerSubscription> findByResource(TriggerType type, String resourceKey) {
        return repository.findByTriggerTypeAndResourceKey(type, resourceKey);
    }

    /** 워크플로우 삭제 경로 전용 — 호출자 트랜잭션에 합류한다. */
    @Transactional
    public void deleteByWorkflowId(UUID workflowId) {
        repository.deleteByWorkflowId(workflowId);
    }

    private record AppTrigger(String nodeId, TriggerType type, Map<?, ?> config) {
    }

    private AppTrigger findAppTrigger(List<Map<String, Object>> nodes) {
        for (Map<String, Object> node : nodes) {
            if (!"TRIGGER".equals(node.get("type"))) {
                continue;
            }
            TriggerType type = node.get("event") instanceof String event
                ? eventCatalog.triggerTypeOf(event).orElse(null) : null;
            if (!subscribes(type)) {
                return null;
            }
            Map<?, ?> config = node.get("config") instanceof Map<?, ?> c ? c : Map.of();
            return new AppTrigger(String.valueOf(node.get("id")), type, config);
        }
        return null;
    }
}
