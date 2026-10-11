package com.ieum.workflowcore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.AppTriggerSubscription;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.executor.AppTriggerAccessVerifier;
import com.ieum.workflowcore.repository.AppTriggerSubscriptionRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AppTriggerSubscriptionServiceTest {

    private final AppTriggerSubscriptionRepository repository = org.mockito.Mockito.mock(AppTriggerSubscriptionRepository.class);
    private final AppTriggerAccessVerifier verifier = org.mockito.Mockito.mock(AppTriggerAccessVerifier.class);
    private final AppTriggerSubscriptionService service = new AppTriggerSubscriptionService(repository, verifier);

    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    private Workflow workflow(boolean active) {
        Workflow w = Workflow.builder().userId(userId).name("w").isActive(active).build();
        ReflectionTestUtils.setField(w, "id", workflowId);
        return w;
    }

    private static Map<String, Object> trigger(String id, Map<String, Object> config) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("type", "TRIGGER");
        node.put("config", config);
        return node;
    }

    private static Map<String, Object> gmailNode(String id, String query) {
        Map<String, Object> config = new HashMap<>(Map.of("triggerType", "GMAIL_NEW_EMAIL", "serviceType", "GOOGLE"));
        if (query != null) {
            config.put("query", query);
        }
        return trigger(id, config);
    }

    private AppTriggerSubscription savedRow() {
        ArgumentCaptor<AppTriggerSubscription> captor = ArgumentCaptor.forClass(AppTriggerSubscription.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("활성 + Gmail 노드 → 새 행, query 스냅샷, 커서는 지금")
    void newGmailRowStartsCursorAtNow() {
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of());

        service.sync(workflow(true), List.of(gmailNode("t1", "  from:boss@x.com ")));

        AppTriggerSubscription row = savedRow();
        assertThat(row.getWorkflowId()).isEqualTo(workflowId);
        assertThat(row.getNodeId()).isEqualTo("t1");
        assertThat(row.getUserId()).isEqualTo(userId);
        assertThat(row.getTriggerType()).isEqualTo(TriggerType.GMAIL_NEW_EMAIL);
        assertThat(row.getConfig()).isEqualTo(Map.of("query", "from:boss@x.com"));
        assertThat(row.getResourceKey()).isNull();
        assertThat(row.getNextPollAt()).isBeforeOrEqualTo(LocalDateTime.now());
        assertThat(row.getCursor()).containsKey("afterSec");
    }

    @Test
    @DisplayName("빈 query는 스냅샷에서 빠진다(in:inbox는 질의 조립 시점에 붙는다)")
    void blankQueryIsDropped() {
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of());

        service.sync(workflow(true), List.of(gmailNode("t1", "   ")));

        assertThat(savedRow().getConfig()).isEmpty();
    }

    @Test
    @DisplayName("GitHub 노드 → resource_key = repoId 십진 문자열, 스냅샷 {repoId: Long}, 검증기에 스냅샷 전달")
    void githubRowUsesRepoId() {
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of());
        Map<String, Object> config = new HashMap<>(Map.of(
            "triggerType", "GITHUB_NEW_ISSUE", "serviceType", "GITHUB", "repo", "o/r", "repoId", 123456));

        service.sync(workflow(true), List.of(trigger("t1", config)));

        AppTriggerSubscription row = savedRow();
        assertThat(row.getResourceKey()).isEqualTo("123456");
        assertThat(row.getConfig()).isEqualTo(Map.of("repoId", 123456L));
        assertThat(row.getNextPollAt()).isNull();
        verify(verifier).verify(userId, TriggerType.GITHUB_NEW_ISSUE, Map.of("repoId", 123456L));
    }

    @Test
    @DisplayName("같은 노드·같은 repoId 재저장은 검증기를 부르지 않는다(숫자·문자열 혼용 포함), repoId가 바뀌면 부른다")
    void unchangedResourceIsNotRechecked() {
        AppTriggerSubscription existing = AppTriggerSubscription.create(workflowId, "t1", userId,
            TriggerType.GITHUB_NEW_ISSUE, "123", Map.of("repoId", 123L), LocalDateTime.now());
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of(existing));

        service.sync(workflow(true), List.of(trigger("t1", new HashMap<>(Map.of(
            "triggerType", "GITHUB_NEW_ISSUE", "repoId", "123")))));
        verify(verifier, never()).verify(any(), any(), any());

        service.sync(workflow(true), List.of(trigger("t1", new HashMap<>(Map.of(
            "triggerType", "GITHUB_NEW_ISSUE", "repoId", "124")))));
        verify(verifier).verify(eq(userId), eq(TriggerType.GITHUB_NEW_ISSUE), any());
    }

    @Test
    @DisplayName("[Review Focus 4] repoId 숫자 77과 문자열 \"77\"은 같은 resource_key, 양의 정수가 아니면 400(INVALID_WORKFLOW)·행 없음")
    void repoIdNormalization() {
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of());
        service.sync(workflow(true), List.of(trigger("t1", new HashMap<>(Map.of(
            "triggerType", "GITHUB_NEW_ISSUE", "repoId", "77")))));
        assertThat(savedRow().getResourceKey()).isEqualTo("77");

        org.mockito.Mockito.clearInvocations(repository);
        service.sync(workflow(true), List.of(trigger("t1", new HashMap<>(Map.of(
            "triggerType", "GITHUB_NEW_ISSUE", "repoId", 77)))));
        assertThat(savedRow().getResourceKey()).isEqualTo("77");

        org.mockito.Mockito.clearInvocations(repository);
        assertThatThrownBy(() -> service.sync(workflow(true), List.of(trigger("t1", new HashMap<>(Map.of(
            "triggerType", "GITHUB_NEW_ISSUE", "repoId", "abc"))))))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("검증기가 거부하면 예외가 그대로 나가고 행을 쓰지 않는다")
    void verifierRejectionPropagates() {
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of());
        willThrow(new CustomException(ErrorCode.INVALID_INPUT, "레포 접근 불가"))
            .given(verifier).verify(eq(userId), eq(TriggerType.GITHUB_NEW_ISSUE), any());

        assertThatThrownBy(() -> service.sync(workflow(true), List.of(trigger("t1", new HashMap<>(Map.of(
            "triggerType", "GITHUB_NEW_ISSUE", "repoId", 1))))))
            .isInstanceOf(CustomException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("활성 + 최상위만 앱 트리거, 노드 선언 없음 → 400(활성인데 구독 행 없어 미발화 방지), 행을 건드리지 않는다")
    void appTriggerWithoutNodeDeclarationIsRejected() {
        Workflow w = workflow(true);
        ReflectionTestUtils.setField(w, "triggerType", TriggerType.GMAIL_NEW_EMAIL);

        assertThatThrownBy(() -> service.sync(w, List.of(trigger("t1", new HashMap<>()))))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW);
        verify(repository, never()).findByWorkflowId(any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("비활성 워크플로우 → 기존 행 삭제, 검증 안 함")
    void inactiveDeletes() {
        AppTriggerSubscription existing = AppTriggerSubscription.create(workflowId, "t1", userId,
            TriggerType.GMAIL_NEW_EMAIL, null, Map.of(), LocalDateTime.now());
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of(existing));

        service.sync(workflow(false), List.of(gmailNode("t1", null)));

        verify(repository).delete(existing);
        verify(repository, never()).save(any());
        verify(verifier, never()).verify(any(), any(), any());
    }

    @Test
    @DisplayName("트리거가 SCHEDULE로 바뀜 → 행 삭제")
    void nonAppTriggerDeletes() {
        AppTriggerSubscription existing = AppTriggerSubscription.create(workflowId, "t1", userId,
            TriggerType.GMAIL_NEW_EMAIL, null, Map.of(), LocalDateTime.now());
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of(existing));

        service.sync(workflow(true), List.of(trigger("t1", new HashMap<>(Map.of("triggerType", "SCHEDULE", "cron", "0 * * * *")))));

        verify(repository).delete(existing);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("같은 노드·같은 query 재저장 → 기존 행 유지(커서 유지), 새 행 없음")
    void sameQueryKeepsCursor() {
        AppTriggerSubscription existing = AppTriggerSubscription.create(workflowId, "t1", userId,
            TriggerType.GMAIL_NEW_EMAIL, null, Map.of("query", "from:x"), LocalDateTime.now().minusDays(1));
        Object cursorBefore = existing.getCursor();
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of(existing));

        service.sync(workflow(true), List.of(gmailNode("t1", "from:x")));

        assertThat(existing.getCursor()).isSameAs(cursorBefore);
        verify(repository, never()).save(any());
        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("같은 노드·query 변경 → 같은 행에서 커서 리셋(삭제·재삽입 아님 — UNIQUE 경합 회피)")
    void changedQueryResetsCursor() {
        AppTriggerSubscription existing = AppTriggerSubscription.create(workflowId, "t1", userId,
            TriggerType.GMAIL_NEW_EMAIL, null, Map.of("query", "from:x"), LocalDateTime.now().minusDays(1));
        long before = ((Number) existing.getCursor().get("afterSec")).longValue();
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of(existing));

        service.sync(workflow(true), List.of(gmailNode("t1", "from:y")));

        assertThat(((Number) existing.getCursor().get("afterSec")).longValue()).isGreaterThan(before);
        assertThat(existing.getConfig()).isEqualTo(Map.of("query", "from:y"));
        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("트리거 노드가 교체됨(id 변경) → 옛 행 삭제 + 새 행")
    void replacedNodeRecreates() {
        AppTriggerSubscription old = AppTriggerSubscription.create(workflowId, "old", userId,
            TriggerType.GMAIL_NEW_EMAIL, null, Map.of(), LocalDateTime.now());
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of(old));

        service.sync(workflow(true), List.of(gmailNode("new", null)));

        verify(repository).delete(old);
        assertThat(savedRow().getNodeId()).isEqualTo("new");
    }

    @Test
    @DisplayName("triggerType 없는 TRIGGER 노드는 건너뛴다(TriggerNodeSchedule.find와 같은 선택) — 앞의 Gmail 노드가 구독된다")
    void triggerNodeWithoutTypeIsSkipped() {
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of());

        service.sync(workflow(true), List.of(gmailNode("t1", null), trigger("t2", new HashMap<>())));

        AppTriggerSubscription row = savedRow();
        assertThat(row.getNodeId()).isEqualTo("t1");
        assertThat(row.getTriggerType()).isEqualTo(TriggerType.GMAIL_NEW_EMAIL);
    }

    @Test
    @DisplayName("query 생략 행에 빈 query로 재저장 → 같은 값으로 보고 커서 유지")
    void blankQueryEqualsOmittedQuery() {
        AppTriggerSubscription existing = AppTriggerSubscription.create(workflowId, "t1", userId,
            TriggerType.GMAIL_NEW_EMAIL, null, Map.of(), LocalDateTime.now().minusDays(1));
        Object cursorBefore = existing.getCursor();
        given(repository.findByWorkflowId(workflowId)).willReturn(List.of(existing));

        service.sync(workflow(true), List.of(gmailNode("t1", "  ")));

        assertThat(existing.getCursor()).isSameAs(cursorBefore);
        assertThat(existing.getConfig().get("query")).isNull();
    }

    private static Map<String, Object> repoConfig(Object repoId) {
        Map<String, Object> config = new HashMap<>();
        config.put("triggerType", "GITHUB_NEW_ISSUE");
        config.put("repoId", repoId);
        return config;
    }

    @Test
    @DisplayName("[Review Focus 4] 숫자 123과 문자열 \"123\"·\" 123 \"은 같은 repoId")
    void numberAndDigitStringGiveSameKey() {
        assertThat(AppTriggerSubscriptionService.githubRepoId(repoConfig(123))).contains(123L);
        assertThat(AppTriggerSubscriptionService.githubRepoId(repoConfig(123L))).contains(123L);
        assertThat(AppTriggerSubscriptionService.githubRepoId(repoConfig("123"))).contains(123L);
        assertThat(AppTriggerSubscriptionService.githubRepoId(repoConfig(" 123 "))).contains(123L);
    }

    @Test
    @DisplayName("repoId가 없거나 빈 문자열이면 미설정(empty) — 저장을 막지 않는다")
    void missingIsEmpty() {
        assertThat(AppTriggerSubscriptionService.githubRepoId(repoConfig(null))).isEmpty();
        assertThat(AppTriggerSubscriptionService.githubRepoId(repoConfig("  "))).isEmpty();
        assertThat(AppTriggerSubscriptionService.githubRepoId(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "12a", "1.5", "99999999999999999999", "owner/name"})
    @DisplayName("양의 정수 문자열이 아니면 INVALID_WORKFLOW")
    void malformedIsRejected(String raw) {
        assertThatThrownBy(() -> AppTriggerSubscriptionService.githubRepoId(repoConfig(raw)))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW));
    }

    @Test
    @DisplayName("숫자형 0·음수·실수·불리언도 거부")
    void malformedNonString() {
        for (Object raw : new Object[] {0, -5L, 1.5d, true}) {
            assertThatThrownBy(() -> AppTriggerSubscriptionService.githubRepoId(repoConfig(raw)))
                .isInstanceOf(CustomException.class);
        }
    }
}
