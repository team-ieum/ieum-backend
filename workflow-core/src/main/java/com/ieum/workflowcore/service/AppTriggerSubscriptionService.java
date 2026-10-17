package com.ieum.workflowcore.service;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.AppTriggerSubscription;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.executor.AppTriggerAccessVerifier;
import com.ieum.workflowcore.repository.AppTriggerSubscriptionRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱 트리거 구독 행의 동기화·클레임·폴링 기록. Gmail 호출·실행 생성은 api 모듈 몫이고 여기는 행만 다룬다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AppTriggerSubscriptionService {

    private static final Pattern GITHUB_REPO_ID = Pattern.compile("[1-9][0-9]{0,18}");

    private final AppTriggerSubscriptionRepository repository;
    private final AppTriggerAccessVerifier accessVerifier;

    /**
     * 저장될 정의·활성 상태에 구독 행을 맞춘다. {@code WorkflowCrudService}의 생성·수정·AI 저장·활성화·비활성화가
     * 같은 트랜잭션 안에서 부른다(Quartz와 달리 afterCommit이 아니다 — 같은 PG라 원자적으로 묶고, 검증 거부를 400으로 낸다).
     *
     * <p>마지막 TRIGGER 노드(TriggerNodeSchedule.find와 같은 선택 — {@code config.triggerType} 없는 노드는 건너뜀)의
     * {@code config.triggerType}이 앱 트리거이고 워크플로우가 활성이면 그 노드 id로 upsert, 아니면 그 워크플로우의 행을 전부
     * 지운다. 같은 노드 id면 행을 재사용해 재구성한다 — 지우고 같은 (workflow_id, node_id)로 다시 넣으면 Hibernate flush
     * 순서(insert가 delete보다 먼저)로 UNIQUE 위반이다.
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
        Map<String, Object> snapshot = snapshot(trigger.type(), trigger.config());
        String resourceKey = trigger.type() == TriggerType.GITHUB_NEW_ISSUE && snapshot.get("repoId") != null
            ? String.valueOf(snapshot.get("repoId")) : null;
        // 같은 행·같은 리소스면 다시 묻지 않는다 — GitHub 장애(503)가 무관한 편집·AI 저장을 막지 않게.
        // 비활성화가 행을 지우므로 생성·활성화는 늘 새 행 → 늘 검증된다. 접근 회수는 BE-81 수신 시점 재검증이 막는다.
        boolean unchanged = keep != null && keep.getTriggerType() == trigger.type()
            && Objects.equals(keep.getResourceKey(), resourceKey);
        if (!unchanged) {
            accessVerifier.verify(workflow.getUserId(), trigger.type(), snapshot);
        }
        LocalDateTime now = LocalDateTime.now();
        if (keep != null) {
            keep.reconfigure(trigger.type(), workflow.getUserId(), resourceKey, snapshot, now);
        } else {
            repository.save(AppTriggerSubscription.create(workflow.getId(), trigger.nodeId(), workflow.getUserId(),
                trigger.type(), resourceKey, snapshot, now));
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

    private static AppTrigger findAppTrigger(List<Map<String, Object>> nodes) {
        AppTrigger found = null;
        for (Map<String, Object> node : nodes) {
            if (!"TRIGGER".equals(node.get("type")) || !(node.get("config") instanceof Map<?, ?> config)
                    || config.get("triggerType") == null) {
                continue;
            }
            TriggerType type = TriggerType.appTriggerOf(config.get("triggerType"));
            found = type == null ? null : new AppTrigger(String.valueOf(node.get("id")), type, config);
        }
        return found;
    }

    /**
     * 구독에 필요한 키만 남긴다 — 나머지(serviceType·repo·label 등)는 저장하지 않는다. blank query는 키 생략(=null)과 같게
     * 빠진다 — 다르게 보면 저장마다 커서가 리셋돼 메일이 유실된다. HashMap: GitHub repoId가 null일 수 있다.
     */
    private static Map<String, Object> snapshot(TriggerType type, Map<?, ?> config) {
        Map<String, Object> snapshot = new HashMap<>();
        if (type == TriggerType.GMAIL_NEW_EMAIL) {
            String query = gmailQuery(config);
            if (query != null) {
                snapshot.put("query", query);
            }
        } else {
            snapshot.put("repoId", githubRepoId(config).orElse(null));
        }
        return snapshot;
    }

    /** Gmail 검색식 정규화 — trim, blank는 null. 스냅샷과 실제 검색식(GmailTriggerClient.baseQuery)이 함께 쓴다 */
    public static String gmailQuery(Map<?, ?> config) {
        return config != null && config.get("query") instanceof String q && !q.isBlank() ? q.trim() : null;
    }

    /**
     * GitHub 트리거 config의 {@code repoId} — 라우팅({@code resource_key})과 접근 검증의 키. FE 드롭다운은 문자열("123"),
     * agent·Swagger는 숫자(123)로 저장하므로 둘 다 받는다. 스냅샷과 노드 테스트(api GitHubIssueSource.fetchLatest)가 함께 쓴다.
     *
     * @return 미설정(없음·blank)이면 empty. 양의 정수가 아니면 {@code INVALID_WORKFLOW}(400)
     */
    public static Optional<Long> githubRepoId(Map<?, ?> config) {
        Object value = config == null ? null : config.get("repoId");
        if (value == null || value instanceof String s && s.isBlank()) {
            return Optional.empty();
        }
        if ((value instanceof Integer || value instanceof Long) && ((Number) value).longValue() > 0) {
            return Optional.of(((Number) value).longValue());
        }
        if (value instanceof String s && GITHUB_REPO_ID.matcher(s.strip()).matches()) {
            try {
                return Optional.of(Long.parseLong(s.strip()));
            } catch (NumberFormatException ignored) {
                // 19자리지만 Long 범위 밖 — 아래와 같은 오류
            }
        }
        throw new CustomException(ErrorCode.INVALID_WORKFLOW, "GitHub 트리거의 repoId가 올바르지 않습니다.");
    }
}
