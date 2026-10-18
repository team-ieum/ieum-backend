package com.ieum.workflowcore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.domain.AppTriggerSubscription;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.repository.AppTriggerSubscriptionRepository;
import com.ieum.workflowcore.trigger.PollStart;
import com.ieum.workflowcore.trigger.TriggerHandler;
import com.ieum.workflowcore.trigger.TriggerHandlerRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 구독 동기화의 일반 흐름 — 핸들러는 테스트 안의 가짜다. Gmail·GitHub 고유 규칙(blank query 생략, 커서 afterSec,
 * repoId 정규화·거부, resource_key)은 api {@code GmailTriggerClientTest}·{@code GitHubIssueSourceTest}가 고정한다.
 */
class AppTriggerSubscriptionServiceTest {

    /** 폴링형 가짜(GMAIL_NEW_EMAIL 자리) — 스냅샷 {@code {query?}}, 시작 커서 {@code {startedAt}}, 즉시 폴링 */
    static class FakePolling implements TriggerHandler {
        @Override
        public TriggerType type() {
            return TriggerType.GMAIL_NEW_EMAIL;
        }

        @Override
        public boolean subscribes() {
            return true;
        }

        @Override
        public Map<String, Object> snapshot(Map<?, ?> config) {
            Map<String, Object> snapshot = new HashMap<>();
            if (config.get("query") != null) {
                snapshot.put("query", config.get("query"));
            }
            return snapshot;
        }

        @Override
        public PollStart pollStart(LocalDateTime now) {
            return new PollStart(Map.of("startedAt", now.toString()), now);
        }
    }

    /** 수신형 가짜(GITHUB_NEW_ISSUE 자리) — 스냅샷 {@code {repoId}} 원본 그대로, resource_key = 문자열, 폴링 없음 */
    static class FakeReceiving implements TriggerHandler {
        @Override
        public TriggerType type() {
            return TriggerType.GITHUB_NEW_ISSUE;
        }

        @Override
        public boolean subscribes() {
            return true;
        }

        @Override
        public Map<String, Object> snapshot(Map<?, ?> config) {
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("repoId", config.get("repoId"));
            return snapshot;
        }

        @Override
        public String resourceKey(Map<String, Object> snapshot) {
            Object repoId = snapshot.get("repoId");
            return repoId == null ? null : String.valueOf(repoId);
        }
    }

    /** 주어진 구독형 핸들러 + 나머지 TriggerType은 구독하지 않는 람다 — 레지스트리가 전 타입을 덮는다 */
    private static List<TriggerHandler> handlers(TriggerHandler... subscribing) {
        List<TriggerHandler> all = new ArrayList<>(List.of(subscribing));
        for (TriggerType type : TriggerType.values()) {
            if (all.stream().noneMatch(h -> h.type() == type)) {
                all.add(() -> type);
            }
        }
        return all;
    }

    private static final EventCatalog CATALOG = EventCatalog.loadFromClasspath();
    private final AppTriggerSubscriptionRepository repository = mock(AppTriggerSubscriptionRepository.class);
    private final FakePolling polling = spy(new FakePolling());
    private final FakeReceiving receiving = spy(new FakeReceiving());
    private final AppTriggerSubscriptionService service = new AppTriggerSubscriptionService(
        repository, new TriggerHandlerRegistry(handlers(polling, receiving)), CATALOG);

    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    private Workflow workflow(boolean active) {
        Workflow w = Workflow.builder().userId(userId).name("w").isActive(active).build();
        ReflectionTestUtils.setField(w, "id", workflowId);
        return w;
    }

    private static Map<String, Object> trigger(String id, String event, Map<String, Object> config) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("type", "TRIGGER");
        node.put("event", event);
        node.put("config", config);
        return node;
    }

    private static Map<String, Object> gmailNode(String id, String query) {
        Map<String, Object> config = new HashMap<>();
        if (query != null) {
            config.put("query", query);
        }
        return trigger(id, "gmail.new_email", config);
    }

    private static Map<String, Object> githubNode(String id, Object repoId) {
        Map<String, Object> config = new HashMap<>();
        config.put("repoId", repoId);
        return trigger(id, "github.new_issue", config);
    }

    private AppTriggerSubscription savedRow() {
        ArgumentCaptor<AppTriggerSubscription> captor = ArgumentCaptor.forClass(AppTriggerSubscription.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("subscribes(type) = 그 핸들러의 subscribes(), null이면 false")
    void subscribesFollowsHandler() {
        assertThat(service.subscribes(TriggerType.GMAIL_NEW_EMAIL)).isTrue();
        assertThat(service.subscribes(TriggerType.GITHUB_NEW_ISSUE)).isTrue();
        assertThat(service.subscribes(TriggerType.MANUAL)).isFalse();
        assertThat(service.subscribes(TriggerType.WEBHOOK)).isFalse();
        assertThat(service.subscribes(TriggerType.SCHEDULE)).isFalse();
        assertThat(service.subscribes(null)).isFalse();
    }

    @Test
    @DisplayName("활성 + 구독형 노드 → 새 행: 스냅샷·커서·다음 폴링은 핸들러가 정한다, 새 행은 접근 검증을 거친다")
    void newRowUsesHandlerSnapshotAndPollStart() {
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of());

        service.sync(workflow(true), List.of(gmailNode("t1", "from:boss@x.com")));

        AppTriggerSubscription row = savedRow();
        assertThat(row.getWorkflowId()).isEqualTo(workflowId);
        assertThat(row.getNodeId()).isEqualTo("t1");
        assertThat(row.getUserId()).isEqualTo(userId);
        assertThat(row.getTriggerType()).isEqualTo(TriggerType.GMAIL_NEW_EMAIL);
        assertThat(row.getConfig()).isEqualTo(Map.of("query", "from:boss@x.com"));
        assertThat(row.getResourceKey()).isNull();
        assertThat(row.getCursor()).containsKey("startedAt");
        assertThat(row.getNextPollAt()).isNotNull().isBeforeOrEqualTo(LocalDateTime.now());
        verify(polling).verifyAccess(userId, Map.of("query", "from:boss@x.com"));
    }

    @Test
    @DisplayName("수신형 노드 → resource_key = 핸들러 resourceKey(스냅샷), 폴링 없음, 검증기에 스냅샷 전달")
    void receivingRowUsesResourceKey() {
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of());

        service.sync(workflow(true), List.of(githubNode("t1", 123456)));

        AppTriggerSubscription row = savedRow();
        assertThat(row.getResourceKey()).isEqualTo("123456");
        assertThat(row.getConfig()).isEqualTo(Map.of("repoId", 123456));
        assertThat(row.getCursor()).isNull();
        assertThat(row.getNextPollAt()).isNull();
        verify(receiving).verifyAccess(userId, Map.of("repoId", 123456));
    }

    @Test
    @DisplayName("같은 노드·같은 resource_key 재저장은 검증하지 않는다, resource_key가 바뀌면 검증한다")
    void unchangedResourceIsNotRechecked() {
        AppTriggerSubscription existing = AppTriggerSubscription.create(workflowId, "t1", userId,
            TriggerType.GITHUB_NEW_ISSUE, "123", Map.of("repoId", 123L), PollStart.NONE);
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of(existing));

        service.sync(workflow(true), List.of(githubNode("t1", "123")));
        verify(receiving, never()).verifyAccess(any(), any());

        service.sync(workflow(true), List.of(githubNode("t1", "124")));
        verify(receiving).verifyAccess(eq(userId), any());
        assertThat(existing.getResourceKey()).isEqualTo("124");
    }

    @Test
    @DisplayName("[Review Focus] 접근 검증 거부 → 그 예외가 그대로 나가고 새 행을 쓰지 않으며 기존 행도 바꾸지 않는다")
    void accessRejectionPropagatesWithoutRow() {
        CustomException rejection = new CustomException(ErrorCode.INVALID_INPUT, "레포 접근 불가");
        willThrow(rejection).given(receiving).verifyAccess(eq(userId), any());

        given(repository.findByWorkflowId(workflowId)).willReturn(List.of());
        assertThatThrownBy(() -> service.sync(workflow(true), List.of(githubNode("t1", 1)))).isSameAs(rejection);
        verify(repository, never()).save(any());

        AppTriggerSubscription existing = AppTriggerSubscription.create(workflowId, "t1", userId,
            TriggerType.GITHUB_NEW_ISSUE, "1", Map.of("repoId", 1), PollStart.NONE);
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of(existing));
        assertThatThrownBy(() -> service.sync(workflow(true), List.of(githubNode("t1", 2)))).isSameAs(rejection);
        assertThat(existing.getResourceKey()).isEqualTo("1");
        assertThat(existing.getConfig()).isEqualTo(Map.of("repoId", 1));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("핸들러 스냅샷이 거부(형식 오류)하면 그 예외가 그대로 나가고 검증·행 쓰기가 없다")
    void snapshotRejectionWritesNothing() {
        CustomException malformed = new CustomException(ErrorCode.INVALID_WORKFLOW, "repoId 형식 오류");
        willThrow(malformed).given(receiving).snapshot(any());
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of());

        assertThatThrownBy(() -> service.sync(workflow(true), List.of(githubNode("t1", "abc")))).isSameAs(malformed);

        verify(receiving, never()).verifyAccess(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("활성 + PG만 앱 트리거, 노드에 앱 트리거 없음 → 거부하지 않고 행을 지운다(PG 값은 저장 때 노드에서 계산 — IEUM-BE-92)")
    void topLevelAppTriggerWithoutNodeDeletesRowsWithoutRejecting() {
        Workflow w = workflow(true);
        ReflectionTestUtils.setField(w, "triggerType", TriggerType.GMAIL_NEW_EMAIL);
        AppTriggerSubscription existing = AppTriggerSubscription.create(workflowId, "t1", userId,
            TriggerType.GMAIL_NEW_EMAIL, null, Map.of(), PollStart.NONE);
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of(existing));

        service.sync(w, List.of(trigger("t1", "manual.run", new HashMap<>())));

        verify(repository).delete(existing);
        verify(repository, never()).save(any());
        verify(polling, never()).verifyAccess(any(), any());
    }

    @Test
    @DisplayName("비활성 워크플로우 → 기존 행 삭제, 검증 안 함")
    void inactiveDeletes() {
        AppTriggerSubscription existing = AppTriggerSubscription.create(workflowId, "t1", userId,
            TriggerType.GMAIL_NEW_EMAIL, null, Map.of(), PollStart.NONE);
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of(existing));

        service.sync(workflow(false), List.of(gmailNode("t1", null)));

        verify(repository).delete(existing);
        verify(repository, never()).save(any());
        verify(polling, never()).verifyAccess(any(), any());
    }

    @Test
    @DisplayName("트리거가 SCHEDULE로 바뀜(구독하지 않는 핸들러) → 행 삭제")
    void nonAppTriggerDeletes() {
        AppTriggerSubscription existing = AppTriggerSubscription.create(workflowId, "t1", userId,
            TriggerType.GMAIL_NEW_EMAIL, null, Map.of(), PollStart.NONE);
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of(existing));

        service.sync(workflow(true), List.of(trigger("t1", "schedule.every", new HashMap<>(Map.of("cron", "0 * * * *")))));

        verify(repository).delete(existing);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("[Review Focus] 같은 노드·같은 스냅샷 재저장 → 기존 행 유지(커서·다음 폴링 유지), 새 행 없음")
    void sameSnapshotKeepsCursor() {
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        AppTriggerSubscription existing = AppTriggerSubscription.create(workflowId, "t1", userId,
            TriggerType.GMAIL_NEW_EMAIL, null, Map.of("query", "from:x"), new FakePolling().pollStart(yesterday));
        Object cursorBefore = existing.getCursor();
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of(existing));

        service.sync(workflow(true), List.of(gmailNode("t1", "from:x")));

        assertThat(existing.getCursor()).isSameAs(cursorBefore);
        assertThat(existing.getNextPollAt()).isEqualTo(yesterday);
        verify(repository, never()).save(any());
        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("[Review Focus] 같은 노드·스냅샷 변경 → 같은 행에서 핸들러 pollStart로 리셋(삭제·재삽입 아님 — UNIQUE 경합 회피)")
    void changedSnapshotResetsCursor() {
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        AppTriggerSubscription existing = AppTriggerSubscription.create(workflowId, "t1", userId,
            TriggerType.GMAIL_NEW_EMAIL, null, Map.of("query", "from:x"), new FakePolling().pollStart(yesterday));
        existing.recordPoll(existing.getCursor(), "ERR", yesterday, yesterday.plusMinutes(5));
        Object cursorBefore = existing.getCursor();
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of(existing));

        service.sync(workflow(true), List.of(gmailNode("t1", "from:y")));

        assertThat(existing.getConfig()).isEqualTo(Map.of("query", "from:y"));
        assertThat(existing.getCursor()).containsKey("startedAt").isNotEqualTo(cursorBefore);
        assertThat(existing.getNextPollAt()).isAfter(yesterday.plusMinutes(5));
        assertThat(existing.getLastError()).isNull();
        verify(repository, never()).delete(any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("트리거 노드가 교체됨(id 변경) → 옛 행 삭제 + 새 행")
    void replacedNodeRecreates() {
        AppTriggerSubscription old = AppTriggerSubscription.create(workflowId, "old", userId,
            TriggerType.GMAIL_NEW_EMAIL, null, Map.of(), PollStart.NONE);
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of(old));

        service.sync(workflow(true), List.of(gmailNode("new", null)));

        verify(repository).delete(old);
        assertThat(savedRow().getNodeId()).isEqualTo("new");
    }

    @Test
    @DisplayName("첫 TRIGGER 노드만 본다 — 첫 번째가 schedule.every면 뒤의 Gmail 노드는 구독되지 않고 기존 행은 지워진다")
    void onlyFirstTriggerNodeCounts() {
        AppTriggerSubscription existing = AppTriggerSubscription.create(workflowId, "t2", userId,
            TriggerType.GMAIL_NEW_EMAIL, null, Map.of(), PollStart.NONE);
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of(existing));

        service.sync(workflow(true), List.of(
            trigger("t1", "schedule.every", new HashMap<>(Map.of("cron", "0 * * * *"))),
            gmailNode("t2", "from:x")));

        verify(repository).delete(existing);
        verify(repository, never()).save(any());

        org.mockito.Mockito.reset(repository);
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of());
        service.sync(workflow(true), List.of(
            gmailNode("t1", null),
            trigger("t2", "schedule.every", new HashMap<>(Map.of("cron", "0 * * * *")))));

        AppTriggerSubscription row = savedRow();
        assertThat(row.getNodeId()).isEqualTo("t1");
        assertThat(row.getTriggerType()).isEqualTo(TriggerType.GMAIL_NEW_EMAIL);
    }

    @Test
    @DisplayName("첫 TRIGGER 노드의 event가 없거나 모르는 값이면 구독하지 않는다(예외 없음) — 비TRIGGER 노드는 건너뛴다")
    void missingOrUnknownEventIsNotSubscribed() {
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of());
        Map<String, Object> ai = new HashMap<>(Map.of("id", "a", "type", "AI", "event", "ai.agent", "config", Map.of()));

        service.sync(workflow(true), List.of(ai, trigger("t1", null, new HashMap<>())));
        service.sync(workflow(true), List.of(trigger("t1", "nope.unknown", new HashMap<>(Map.of("query", "x")))));

        verify(repository, never()).save(any());
        verify(polling, never()).snapshot(any());
        verify(receiving, never()).snapshot(any());
    }
}
