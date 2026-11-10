package com.ieum.api.trigger.forms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.integration.options.GoogleFormsReader;
import com.ieum.api.trigger.AppTriggerFirer;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.deployment.BackgroundOwnership;
import com.ieum.workflowcore.domain.AppTriggerSubscription;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.service.AppTriggerSubscriptionService;
import com.ieum.workflowcore.trigger.PollStart;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class FormsTriggerSweeperTest {

    private static final long AFTER = 1_790_000_000L;
    private static final String FORM = "1FAIp";
    private static final Duration POLL = Duration.ofMinutes(5);

    private final AppTriggerSubscriptionService subscriptions = mock(AppTriggerSubscriptionService.class);
    private final GoogleFormsReader reader = mock(GoogleFormsReader.class);
    private final AppTriggerFirer firer = mock(AppTriggerFirer.class);
    private final FormsTriggerHandler t1 = new FormsTriggerHandler(TriggerType.FORMS_NEW_RESPONSE, reader);
    private final FormsTriggerHandler t2 = new FormsTriggerHandler(TriggerType.FORMS_ANY_RESPONSE, reader);
    private final FormsTriggerSweeper sweeper =
        new FormsTriggerSweeper(subscriptions, reader, List.of(t1, t2), firer, POLL);
    private final ObjectMapper json = new ObjectMapper();
    private final UUID workflowId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private AppTriggerSubscription subscription(TriggerType type, String formId, List<String> seenKeys, long okSec) {
        Map<String, Object> config = new HashMap<>();
        config.put("formId", formId);
        AppTriggerSubscription s = AppTriggerSubscription.create(workflowId, "t1", userId, type, formId, config,
            PollStart.NONE);
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        Map<String, Object> seen = new LinkedHashMap<>();
        seenKeys.forEach(k -> seen.put(k, (int) AFTER));
        // JSON은 Integer로 돌아온다
        ReflectionTestUtils.setField(s, "cursor", Map.of("afterSec", (int) AFTER, "seen", seen, "okSec", okSec));
        given(subscriptions.claimDue(eq(type), any(), eq(50), eq(POLL))).willReturn(List.of(s));
        return s;
    }

    private AppTriggerSubscription subscription(TriggerType type, List<String> seenKeys) {
        return subscription(type, FORM, seenKeys, Instant.now().getEpochSecond());
    }

    private static String iso(long sec) {
        return Instant.ofEpochSecond(sec).toString();
    }

    private JsonNode response(String id, long createSec, long submittedSec) {
        return json.createObjectNode().put("responseId", id).put("createTime", iso(createSec))
            .put("lastSubmittedTime", iso(submittedSec));
    }

    /** 가벼운 목록(시각만)으로 후보를 주고, 단건 조회는 같은 응답을 본문으로 돌려준다 */
    private void listed(JsonNode... responses) {
        given(reader.listResponses(eq(userId), eq(FORM), anyString(), eq(FormsTriggerHandler.LIGHT_FIELDS)))
            .willReturn(List.of(responses));
        for (JsonNode r : responses) {
            given(reader.response(userId, FORM, r.path("responseId").asText())).willReturn(Optional.of(r));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recordedCursor(AppTriggerSubscription s, String expectedError) {
        ArgumentCaptor<Map<String, Object>> cursor = ArgumentCaptor.forClass(Map.class);
        verify(subscriptions).recordPoll(eq(s.getId()), eq(s.getConfig()), cursor.capture(), eq(expectedError), any());
        return cursor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fired(TriggerType type, int times) {
        ArgumentCaptor<Map<String, Object>> outputs = ArgumentCaptor.forClass(Map.class);
        verify(firer, times(times)).fire(eq(workflowId), eq(type), outputs.capture());
        return outputs.getAllValues();
    }

    @Test
    @DisplayName("T1 — 뒤섞인 응답을 createTime 오름차순으로 발화, 필터는 lowSec, 커서 = 마지막 초 + seen")
    @SuppressWarnings("unchecked")
    void firesOldestFirstAndAdvances() {
        AppTriggerSubscription s = subscription(TriggerType.FORMS_NEW_RESPONSE, List.of());
        listed(response("r3", AFTER + 5, AFTER + 5), response("r1", AFTER + 1, AFTER + 1),
            response("r2", AFTER + 3, AFTER + 3));
        given(firer.fire(any(), any(), any())).willReturn(true);

        sweeper.sweep();

        verify(reader).listResponses(userId, FORM, "timestamp >= " + iso(AFTER), FormsTriggerHandler.LIGHT_FIELDS);
        verify(reader, never()).listResponses(any(), any(), any(), isNull());   // 본문 전체 목록은 받지 않는다
        verify(reader, times(1)).questions(userId, FORM);
        verify(reader, times(3)).response(eq(userId), eq(FORM), anyString());
        assertThat(fired(TriggerType.FORMS_NEW_RESPONSE, 3)).extracting(o -> o.get("responseId"))
            .containsExactly("r1", "r2", "r3");
        Map<String, Object> cursor = recordedCursor(s, null);
        assertThat(cursor).containsEntry("afterSec", AFTER + 5);
        assertThat((Map<String, Long>) cursor.get("seen")).containsOnlyKeys("r1", "r2", "r3");
    }

    @Test
    @DisplayName("T1 — 연결 전에 만든 응답이 수정돼 목록에 다시 나와도 발화하지 않는다(createTime 기준)")
    void t1IgnoresEditedOldResponse() {
        AppTriggerSubscription s = subscription(TriggerType.FORMS_NEW_RESPONSE, List.of());
        listed(response("old", AFTER - 100, AFTER + 10));

        sweeper.sweep();

        verify(firer, never()).fire(any(), any(), any());
        verify(reader, never()).questions(any(), any());
        recordedCursor(s, null);
    }

    @Test
    @DisplayName("T2 — 수정된 응답은 새 키(responseId@lastSubmittedTime)로 다시 발화, isUpdate=true")
    @SuppressWarnings("unchecked")
    void t2FiresEditAgainWithIsUpdate() {
        AppTriggerSubscription s = subscription(TriggerType.FORMS_ANY_RESPONSE, List.of("r1@" + iso(AFTER)));
        listed(response("r1", AFTER - 100, AFTER + 10));
        given(firer.fire(any(), any(), any())).willReturn(true);

        sweeper.sweep();

        assertThat(fired(TriggerType.FORMS_ANY_RESPONSE, 1).get(0)).containsEntry("responseId", "r1")
            .containsEntry("isUpdate", true);
        assertThat((Map<String, Long>) recordedCursor(s, null).get("seen"))
            .containsOnlyKeys("r1@" + iso(AFTER), "r1@" + iso(AFTER + 10));
    }

    @Test
    @DisplayName("seen 키는 발화하지 않는다")
    void skipsSeen() {
        subscription(TriggerType.FORMS_NEW_RESPONSE, List.of("r1"));
        listed(response("r1", AFTER, AFTER), response("r2", AFTER + 1, AFTER + 1));
        given(firer.fire(any(), any(), any())).willReturn(true);

        sweeper.sweep();

        assertThat(fired(TriggerType.FORMS_NEW_RESPONSE, 1)).extracting(o -> o.get("responseId")).containsExactly("r2");
    }

    @Test
    @DisplayName("한 폴링에 오래된 20건까지만, 커서는 20번째까지")
    void capsAt20() {
        AppTriggerSubscription s = subscription(TriggerType.FORMS_NEW_RESPONSE, List.of());
        List<JsonNode> responses = new ArrayList<>();
        IntStream.rangeClosed(1, 25).forEach(i -> responses.add(response("r%02d".formatted(i), AFTER + i, AFTER + i)));
        listed(responses.reversed().toArray(JsonNode[]::new));
        given(firer.fire(any(), any(), any())).willReturn(true);

        sweeper.sweep();

        assertThat(fired(TriggerType.FORMS_NEW_RESPONSE, 20).get(19)).containsEntry("responseId", "r20");
        assertThat(recordedCursor(s, null)).containsEntry("afterSec", AFTER + 20);
        // 본문은 고른 20건만 받는다(밀린 25건 전부가 아니다)
        verify(reader, times(20)).response(eq(userId), eq(FORM), anyString());
        verify(reader, never()).response(userId, FORM, "r21");
    }

    @Test
    @DisplayName("단건 조회가 404(그 사이 삭제)면 그 건만 건너뛰고 다음 건을 발화한다 — 건너뛴 건은 seen에 없다")
    @SuppressWarnings("unchecked")
    void skipsResponseGoneOnGet() {
        AppTriggerSubscription s = subscription(TriggerType.FORMS_NEW_RESPONSE, List.of());
        listed(response("r1", AFTER + 1, AFTER + 1), response("r2", AFTER + 2, AFTER + 2));
        given(reader.response(userId, FORM, "r1")).willReturn(Optional.empty());
        given(firer.fire(any(), any(), any())).willReturn(true);

        sweeper.sweep();

        assertThat(fired(TriggerType.FORMS_NEW_RESPONSE, 1)).extracting(o -> o.get("responseId")).containsExactly("r2");
        assertThat((Map<String, Long>) recordedCursor(s, null).get("seen")).containsOnlyKeys("r2");
    }

    @Test
    @DisplayName("작성 중 구독(formId null) — Google을 부르지 않고 성공으로 기록")
    void nullFormIdSkipsGoogle() {
        AppTriggerSubscription s =
            subscription(TriggerType.FORMS_NEW_RESPONSE, null, List.of(), Instant.now().getEpochSecond());

        sweeper.sweep();

        verifyNoInteractions(reader, firer);
        recordedCursor(s, null);
    }

    @Test
    @DisplayName("후보가 없으면 forms.get을 부르지 않는다")
    void noCandidatesSkipsFormsGet() {
        AppTriggerSubscription s = subscription(TriggerType.FORMS_NEW_RESPONSE, List.of("r1"));
        listed(response("r1", AFTER, AFTER), json.createObjectNode().put("responseId", "noTime"));

        sweeper.sweep();

        verify(reader, never()).questions(any(), any());
        recordedCursor(s, null);
    }

    @Test
    @DisplayName("발화 도중 실패하면 오류를 남기고 커서는 발화한 지점까지")
    void failureRecordsErrorAndKeepsFiredCursor() {
        AppTriggerSubscription s = subscription(TriggerType.FORMS_NEW_RESPONSE, List.of());
        listed(response("r1", AFTER + 1, AFTER + 1), response("r2", AFTER + 2, AFTER + 2));
        given(firer.fire(any(), any(), any())).willReturn(true)
            .willThrow(new CustomException(ErrorCode.INVALID_WORKFLOW, "깨짐"));

        sweeper.sweep();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(subscriptions).recordPoll(eq(s.getId()), any(), any(), error.capture(), any());
        assertThat(error.getValue()).startsWith("INVALID_WORKFLOW: ");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cursor = ArgumentCaptor.forClass(Map.class);
        verify(subscriptions).recordPoll(any(), any(), cursor.capture(), any(), any());
        assertThat(cursor.getValue()).containsEntry("afterSec", AFTER + 1);
    }

    @Test
    @DisplayName("24시간 넘게 실패만 했으면 지금부터 다시 본다 — 옛 응답은 발화하지 않는다")
    void resetsAfter24hFailures() {
        long okSec = Instant.now().getEpochSecond() - Duration.ofHours(25).toSeconds();
        AppTriggerSubscription s = subscription(TriggerType.FORMS_NEW_RESPONSE, FORM, List.of(), okSec);
        listed(response("r1", AFTER + 1, AFTER + 1));

        sweeper.sweep();

        verify(firer, never()).fire(any(), any(), any());
        assertThat((Long) recordedCursor(s, null).get("afterSec")).isGreaterThan(okSec);
    }

    @Test
    @DisplayName("fire가 false(비활성)면 멈추고 커서를 옮기지 않는다")
    @SuppressWarnings("unchecked")
    void fireFalseStops() {
        AppTriggerSubscription s = subscription(TriggerType.FORMS_NEW_RESPONSE, List.of());
        listed(response("r1", AFTER + 1, AFTER + 1), response("r2", AFTER + 2, AFTER + 2));
        given(firer.fire(any(), any(), any())).willReturn(false);

        sweeper.sweep();

        fired(TriggerType.FORMS_NEW_RESPONSE, 1);
        Map<String, Object> cursor = recordedCursor(s, null);
        assertThat(cursor).containsEntry("afterSec", AFTER);
        assertThat((Map<String, Long>) cursor.get("seen")).isEmpty();
    }

    @Test
    @DisplayName("백그라운드 소유자가 아니면 아무것도 하지 않는다")
    void notOwnerDoesNothing() {
        BackgroundOwnership ownership = mock(BackgroundOwnership.class);
        given(ownership.isOwner()).willReturn(false);
        ReflectionTestUtils.setField(sweeper, "backgroundOwnership", ownership);

        sweeper.sweep();

        verifyNoInteractions(subscriptions, reader, firer);
    }

    @Test
    @DisplayName("스윕 1회에 두 종류를 각각 클레임한다")
    void claimsBothTypes() {
        sweeper.sweep();

        verify(subscriptions).claimDue(eq(TriggerType.FORMS_NEW_RESPONSE), any(), eq(50), eq(POLL));
        verify(subscriptions).claimDue(eq(TriggerType.FORMS_ANY_RESPONSE), any(), eq(50), eq(POLL));
    }
}
