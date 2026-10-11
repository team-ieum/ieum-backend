package com.ieum.api.trigger.gmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.trigger.AppTriggerFirer;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.AppTriggerSubscription;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.service.AppTriggerSubscriptionService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class GmailTriggerSweeperTest {

    private static final long AFTER = 1_790_000_000L;

    private final AppTriggerSubscriptionService subscriptions = mock(AppTriggerSubscriptionService.class);
    private final GmailTriggerClient gmail = mock(GmailTriggerClient.class);
    private final AppTriggerFirer firer = mock(AppTriggerFirer.class);
    private final GmailTriggerSweeper sweeper =
        new GmailTriggerSweeper(subscriptions, gmail, firer, Duration.ofMinutes(5));
    private final ObjectMapper json = new ObjectMapper();
    private final UUID workflowId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private AppTriggerSubscription subscription(List<String> seenIds) {
        AppTriggerSubscription s = AppTriggerSubscription.create(workflowId, "t1", userId,
            TriggerType.GMAIL_NEW_EMAIL, null, Map.of("query", "from:x"), LocalDateTime.now());
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        Map<String, Object> seen = new java.util.LinkedHashMap<>();
        seenIds.forEach(id -> seen.put(id, (int) AFTER));
        // JSON은 Integer로 돌아온다. okSec는 방금 성공한 것으로(24시간 상한에 안 걸리게)
        ReflectionTestUtils.setField(s, "cursor", Map.of("afterSec", (int) AFTER, "seen", seen,
            "okSec", Instant.now().getEpochSecond()));
        given(subscriptions.claimDue(eq(TriggerType.GMAIL_NEW_EMAIL), any(), eq(50), eq(Duration.ofMinutes(5))))
            .willReturn(List.of(s));
        return s;
    }

    private JsonNode message(String id, long sec) {
        return json.createObjectNode().put("id", id).put("threadId", "t").put("internalDate", String.valueOf(sec * 1000));
    }

    /** Gmail 목록은 최신순 — ids는 오래된 것부터 주면 역순으로 돌려준다. */
    private void listed(Map<String, Long> oldestFirst) {
        List<String> newestFirst = new ArrayList<>(oldestFirst.keySet());
        java.util.Collections.reverse(newestFirst);
        given(gmail.listAllMessageIds(userId, "(from:x) after:" + (AFTER - 1))).willReturn(newestFirst);
        oldestFirst.forEach((id, sec) -> given(gmail.getMessage(userId, id)).willReturn(message(id, sec)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recordedCursor(AppTriggerSubscription s, String expectedError) {
        ArgumentCaptor<Map<String, Object>> cursor = ArgumentCaptor.forClass(Map.class);
        verify(subscriptions).recordPoll(eq(s.getId()), eq(s.getConfig()), cursor.capture(), eq(expectedError), any());
        return cursor.getValue();
    }

    @Test
    @DisplayName("새 메일을 오래된 순으로 발화, 커서 = 마지막 초 + lookback 창 안의 발화 id")
    void firesOldestFirstAndAdvances() {
        AppTriggerSubscription s = subscription(List.of());
        listed(new java.util.LinkedHashMap<>() {{
            put("a", AFTER + 1); put("b", AFTER + 5); put("c", AFTER + 5);
        }});
        given(firer.fire(eq(workflowId), eq(TriggerType.GMAIL_NEW_EMAIL), any())).willReturn(true);

        sweeper.sweep();

        ArgumentCaptor<Map<String, Object>> outputs = ArgumentCaptor.forClass(Map.class);
        verify(firer, times(3)).fire(eq(workflowId), eq(TriggerType.GMAIL_NEW_EMAIL), outputs.capture());
        assertThat(outputs.getAllValues()).extracting(o -> o.get("messageId")).containsExactly("a", "b", "c");
        Map<String, Object> cursor = recordedCursor(s, null);
        assertThat(cursor).containsEntry("afterSec", AFTER + 5);
        assertThat((Map<String, Long>) cursor.get("seen")).containsOnlyKeys("a", "b", "c");
    }

    @Test
    @DisplayName("seen에 있는 id·창 이전 초(after:-1 여유분)는 발화하지 않는다")
    void boundarySecondBeforeCursorIsSkipped() {
        AppTriggerSubscription s = subscription(List.of("seen"));
        listed(new java.util.LinkedHashMap<>() {{
            put("older", AFTER - 1); put("seen", AFTER); put("new", AFTER);
        }});
        given(firer.fire(any(), any(), any())).willReturn(true);

        sweeper.sweep();

        ArgumentCaptor<Map<String, Object>> outputs = ArgumentCaptor.forClass(Map.class);
        verify(firer, times(1)).fire(eq(workflowId), eq(TriggerType.GMAIL_NEW_EMAIL), outputs.capture());
        assertThat(outputs.getValue()).containsEntry("messageId", "new");
        verify(gmail, never()).getMessage(userId, "seen");
        Map<String, Object> cursor = recordedCursor(s, null);
        assertThat(cursor).containsEntry("afterSec", AFTER);
        assertThat((Map<String, Long>) cursor.get("seen")).containsOnlyKeys("seen", "new");
    }

    @Test
    @DisplayName("한 폴링에 최대 20건 — 나머지는 다음 폴링, 커서는 20번째까지")
    void firesAtMostTwentyPerPoll() {
        AppTriggerSubscription s = subscription(List.of());
        java.util.LinkedHashMap<String, Long> mails = new java.util.LinkedHashMap<>();
        IntStream.rangeClosed(1, 25).forEach(i -> mails.put("m" + i, AFTER + i));
        listed(mails);
        given(firer.fire(any(), any(), any())).willReturn(true);

        sweeper.sweep();

        verify(firer, times(20)).fire(any(), any(), any());
        verify(gmail, never()).getMessage(userId, "m21");
        assertThat(recordedCursor(s, null)).containsEntry("afterSec", AFTER + 20);
    }

    @Test
    @DisplayName("중간 실패 — 이미 발화한 지점까지 커서 전진 + last_error, 다음 폴링 예약")
    void midwayFailureKeepsProgressAndRecordsError() {
        AppTriggerSubscription s = subscription(List.of());
        listed(new java.util.LinkedHashMap<>() {{ put("a", AFTER + 1); put("b", AFTER + 2); }});
        given(firer.fire(eq(workflowId), eq(TriggerType.GMAIL_NEW_EMAIL), any()))
            .willReturn(true)
            .willThrow(new CustomException(ErrorCode.INVALID_WORKFLOW, "비활성화된 워크플로우입니다."));

        sweeper.sweep();

        Map<String, Object> cursor = recordedCursor(s, "INVALID_WORKFLOW: 비활성화된 워크플로우입니다.");
        assertThat(cursor).containsEntry("afterSec", AFTER + 1);
        assertThat((Map<String, Long>) cursor.get("seen")).containsOnlyKeys("a");
    }

    @Test
    @DisplayName("목록 단계 실패(토큰 만료 등) — 커서 그대로, 오류 기록")
    void listFailureKeepsCursor() {
        AppTriggerSubscription s = subscription(List.of("x"));
        given(gmail.listAllMessageIds(any(), anyString()))
            .willThrow(new CustomException(ErrorCode.AUTHENTICATION_REQUIRED));

        sweeper.sweep();

        Map<String, Object> cursor = recordedCursor(s, "AUTHENTICATION_REQUIRED: " + ErrorCode.AUTHENTICATION_REQUIRED.getMessage());
        assertThat(((Number) cursor.get("afterSec")).longValue()).isEqualTo(AFTER);
        assertThat((Map<String, Long>) cursor.get("seen")).containsOnlyKeys("x");
    }

    @Test
    @DisplayName("단건 조회 403/404(GOOGLE_RESOURCE_NOT_FOUND) — 건너뛰지 않고 멈춘다: 커서 그대로 + 오류 기록")
    void getMessageNotFoundStopsBatch() {
        AppTriggerSubscription s = subscription(List.of());
        listed(new java.util.LinkedHashMap<>() {{ put("gone", AFTER + 1); put("ok", AFTER + 2); }});
        given(gmail.getMessage(userId, "gone")).willThrow(new CustomException(ErrorCode.GOOGLE_RESOURCE_NOT_FOUND));
        given(firer.fire(any(), any(), any())).willReturn(true);

        sweeper.sweep();

        verify(firer, never()).fire(any(), any(), any());
        Map<String, Object> cursor = recordedCursor(s,
            "GOOGLE_RESOURCE_NOT_FOUND: " + ErrorCode.GOOGLE_RESOURCE_NOT_FOUND.getMessage());
        assertThat(((Number) cursor.get("afterSec")).longValue()).isEqualTo(AFTER);
    }

    @Test
    @DisplayName("잘못된 검색식(INVALID_INPUT) — 다른 실패와 같이 커서 그대로 + 오류 기록, 다음 주기 재시도")
    void invalidQueryKeepsCursorAndRetries() {
        AppTriggerSubscription s = subscription(List.of());
        given(gmail.listAllMessageIds(any(), anyString()))
            .willThrow(new CustomException(ErrorCode.INVALID_INPUT, "Google이 요청을 거부했습니다 (HTTP 400)."));

        sweeper.sweep();

        ArgumentCaptor<LocalDateTime> next = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(subscriptions).recordPoll(eq(s.getId()), eq(s.getConfig()), any(),
            eq("INVALID_INPUT: Google이 요청을 거부했습니다 (HTTP 400)."), next.capture());
        assertThat(next.getValue()).isAfter(LocalDateTime.now().plusMinutes(4));
    }

    @Test
    @DisplayName("워크플로우 비활성(발화 false) — 멈추고 커서는 그 앞까지")
    void inactiveStops() {
        AppTriggerSubscription s = subscription(List.of());
        listed(new java.util.LinkedHashMap<>() {{ put("a", AFTER + 1); put("b", AFTER + 2); }});
        given(firer.fire(any(), any(), any())).willReturn(false);

        sweeper.sweep();

        verify(firer, times(1)).fire(any(), any(), any());
        assertThat(((Number) recordedCursor(s, null).get("afterSec")).longValue()).isEqualTo(AFTER);
    }

    @Test
    @DisplayName("recordPoll이 실패해도 남은 클레임 행은 계속 폴링한다")
    void recordPollFailureDoesNotStopSweep() {
        AppTriggerSubscription first = subscription(List.of());
        AppTriggerSubscription second = subscription(List.of());
        given(subscriptions.claimDue(any(), any(), anyInt(), any())).willReturn(List.of(first, second));
        given(gmail.listAllMessageIds(any(), anyString())).willReturn(List.of());
        org.mockito.BDDMockito.willThrow(new IllegalStateException("db down"))
            .given(subscriptions).recordPoll(eq(first.getId()), any(), any(), any(), any());

        sweeper.sweep();

        verify(subscriptions).recordPoll(eq(second.getId()), any(), any(), any(), any());
    }

    @Test
    @DisplayName("커서 이전 메일이 상한을 넘게 나오면(검색식이 after:를 벗어남) 발화 없이 끊고 커서 유지 + 오류 기록")
    void staleFloodStopsPoll() {
        AppTriggerSubscription s = subscription(List.of());
        Map<String, Long> old = new java.util.LinkedHashMap<>();
        IntStream.range(0, 21).forEach(i -> old.put("old" + i, AFTER - 100 + i));
        listed(old);

        sweeper.sweep();

        verify(firer, never()).fire(any(), any(), any());
        Map<String, Object> cursor = recordedCursor(s, "INVALID_INPUT: 검색식이 after: 기간 제한을 벗어납니다.");
        assertThat(((Number) cursor.get("afterSec")).longValue()).isEqualTo(AFTER);
    }

    @Test
    @DisplayName("lookback — 커서보다 이르게 수신됐지만 늦게 색인된 메일은 창 안이면 발화, 창 안의 발화 id는 다시 조회하지 않는다")
    void lateIndexedMailInsideLookbackFires() {
        AppTriggerSubscription s = subscription(List.of());
        long floor = AFTER - 3600;
        Map<String, Object> seen = new java.util.LinkedHashMap<>();
        seen.put("fired", (int) AFTER);
        ReflectionTestUtils.setField(s, "cursor", Map.of("afterSec", (int) AFTER, "floorSec", (int) floor,
            "seen", seen, "okSec", Instant.now().getEpochSecond()));
        long low = AFTER - GmailCursor.LOOKBACK_SEC;
        given(gmail.listAllMessageIds(userId, "(from:x) after:" + (low - 1))).willReturn(List.of("fired", "late"));
        given(gmail.getMessage(userId, "late")).willReturn(message("late", AFTER - 30));
        given(firer.fire(any(), any(), any())).willReturn(true);

        sweeper.sweep();

        verify(gmail, never()).getMessage(userId, "fired");
        ArgumentCaptor<Map<String, Object>> outputs = ArgumentCaptor.forClass(Map.class);
        verify(firer).fire(eq(workflowId), eq(TriggerType.GMAIL_NEW_EMAIL), outputs.capture());
        assertThat(outputs.getValue()).containsEntry("messageId", "late");
        Map<String, Object> cursor = recordedCursor(s, null);
        assertThat(cursor).containsEntry("afterSec", AFTER).containsEntry("floorSec", floor);
        assertThat((Map<String, Long>) cursor.get("seen")).containsOnlyKeys("fired", "late");
    }

    @Test
    @DisplayName("lookback은 구독 시각(floorSec) 아래로 내려가지 않는다 — 활성화 이전 메일은 발화하지 않는다")
    void lookbackNeverGoesBelowFloor() {
        subscription(List.of()); // floorSec 없음 = afterSec
        given(gmail.listAllMessageIds(userId, "(from:x) after:" + (AFTER - 1))).willReturn(List.of());

        sweeper.sweep();

        verify(gmail).listAllMessageIds(userId, "(from:x) after:" + (AFTER - 1));
    }

    @Test
    @DisplayName("24시간 넘게 폴링이 실패만 했으면 커서를 지금으로 리셋 — 재연동 때 밀린 메일이 한꺼번에 발화하지 않게")
    void staleCursorResetsAfterLongFailure() {
        AppTriggerSubscription s = subscription(List.of());
        long dayAgo = Instant.now().getEpochSecond() - GmailTriggerSweeper.MAX_CATCHUP_SEC - 60;
        ReflectionTestUtils.setField(s, "cursor", Map.of("afterSec", (int) AFTER, "okSec", dayAgo));
        given(gmail.listAllMessageIds(any(), anyString())).willReturn(List.of());

        long before = Instant.now().getEpochSecond();
        sweeper.sweep();

        Map<String, Object> cursor = recordedCursor(s, null);
        assertThat(((Number) cursor.get("afterSec")).longValue()).isGreaterThanOrEqualTo(before);
        assertThat(((Number) cursor.get("floorSec")).longValue()).isGreaterThanOrEqualTo(before);
    }

    @Test
    @DisplayName("메일이 하루 넘게 안 와도(afterSec 오래됨) 폴링이 성공해 왔으면 리셋하지 않는다")
    void quietInboxDoesNotReset() {
        AppTriggerSubscription s = subscription(List.of()); // okSec = 지금, afterSec = AFTER(오래 전)
        given(gmail.listAllMessageIds(userId, "(from:x) after:" + (AFTER - 1))).willReturn(List.of());

        sweeper.sweep();

        assertThat(recordedCursor(s, null)).containsEntry("afterSec", AFTER);
    }
}
