package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.catalog.EventDefinition;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 저장할(파생된) 노드 → 워크플로우 트리거 타입·cron 계산과 요청 최상위 값 검증 (IEUM-BE-92).
 * REST 생성·수정과 AI 저장이 같은 규칙을 쓴다.
 */
class TriggerNodeScheduleTest {

    private static final EventCatalog CATALOG = EventCatalog.loadFromClasspath();
    private static final TriggerNodeSchedule SCHEDULE_9AM = new TriggerNodeSchedule(TriggerType.SCHEDULE, "0 9 * * *");

    private static Map<String, Object> node(String id, String type, String event, Object config) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("event", event);
        node.put("config", config);
        return node;
    }

    private static Map<String, Object> trigger(String event, Object config) {
        return node("t-" + event, "TRIGGER", event, config);
    }

    @SafeVarargs
    private static TriggerNodeSchedule compute(Map<String, Object>... nodes) {
        return TriggerNodeSchedule.compute(List.of(nodes), CATALOG);
    }

    private static void assertInvalidWorkflow(ThrowingCallable call, String expectedInMessage) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CustomException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW);
            assertThat(e.getMessage()).contains(expectedInMessage);
        });
    }

    // ── compute ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("schedule.every 트리거면 SCHEDULE과 config.cron 원문을 쓴다 — Quartz 변환은 저장 쪽이 한다")
    void compute_schedule_readsCronRaw() {
        assertThat(compute(node("h", "HTTP", "http.request", new HashMap<>()),
            trigger("schedule.every", Map.of("cron", "0 9 * * *"))))
            .isEqualTo(new TriggerNodeSchedule(TriggerType.SCHEDULE, "0 9 * * *"));
    }

    @Test
    @DisplayName("트리거 종류는 카탈로그 trigger_type이다 — 카탈로그의 실행 가능한 모든 트리거 이벤트")
    void compute_triggerTypeComesFromCatalog() {
        // 실행 가능한 트리거만 돈다 — 실행 불가 이벤트는 파생(NodeDerivation)이 먼저 거부한다. TRIGGER는 builder·generation 중 하나
        List<EventDefinition> triggers = CATALOG.events().stream()
            .filter(event -> event.triggerType() != null && (event.builder() || event.generation())).toList();
        assertThat(triggers).isNotEmpty();
        for (EventDefinition event : triggers) {
            assertThat(compute(trigger(event.name(), new HashMap<>())).triggerType())
                .as(event.name()).isEqualTo(event.triggerType());
        }
    }

    @Test
    @DisplayName("SCHEDULE인데 cron이 없거나 문자열이 아니거나 config가 없으면 cron은 null — 최상위 값으로 채우지 않는다")
    void compute_scheduleWithoutStringCron_isNullCron() {
        TriggerNodeSchedule expected = new TriggerNodeSchedule(TriggerType.SCHEDULE, null);
        assertThat(compute(trigger("schedule.every", new HashMap<>()))).isEqualTo(expected);
        assertThat(compute(trigger("schedule.every", Map.of("cron", 900)))).isEqualTo(expected);
        assertThat(compute(trigger("schedule.every", null))).isEqualTo(expected);
    }

    @Test
    @DisplayName("SCHEDULE이 아닌 트리거는 config.cron이 있어도 cron null이다")
    void compute_nonSchedule_ignoresCron() {
        assertThat(compute(trigger("webhook.catch", Map.of("cron", "0 9 * * *"))))
            .isEqualTo(new TriggerNodeSchedule(TriggerType.WEBHOOK, null));
    }

    @Test
    @DisplayName("TRIGGER 노드가 없으면 MANUAL·cron null — 저장돼 있던 값을 잇지 않는다")
    void compute_noTriggerNode_isManual() {
        TriggerNodeSchedule manual = new TriggerNodeSchedule(TriggerType.MANUAL, null);
        assertThat(TriggerNodeSchedule.compute(List.of(), CATALOG)).isEqualTo(manual);
        assertThat(compute(node("a", "AI", "ai.agent", Map.of("prompt", "p")))).isEqualTo(manual);
    }

    @Test
    @DisplayName("TRIGGER 노드가 여럿이면 첫 번째를 쓴다 — 실행·includeRaw와 같은 선택")
    void compute_multipleTriggers_firstWins() {
        assertThat(compute(trigger("webhook.catch", new HashMap<>()),
            trigger("schedule.every", Map.of("cron", "0 9 * * *"))))
            .isEqualTo(new TriggerNodeSchedule(TriggerType.WEBHOOK, null));
    }

    @Test
    @DisplayName("TRIGGER 노드의 event가 없거나 트리거 이벤트가 아니면 INVALID_WORKFLOW — 파생을 거치지 않은 노드 방어")
    void compute_triggerWithoutTriggerEvent_isInvalidWorkflow() {
        assertInvalidWorkflow(() -> compute(trigger("http.request", new HashMap<>())), "http.request");
        assertInvalidWorkflow(() -> compute(trigger(null, new HashMap<>())), "TRIGGER");
    }

    // ── assertMatchesRequest ────────────────────────────────────────────────

    @Test
    @DisplayName("요청 최상위 값이 null이면 통과한다")
    void assert_nullsPass() {
        assertThatCode(() -> SCHEDULE_9AM.assertMatchesRequest(null, null)).doesNotThrowAnyException();
        assertThatCode(() -> new TriggerNodeSchedule(TriggerType.MANUAL, null).assertMatchesRequest(null, null))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("triggerType이 같고 cron이 Quartz로 정규화해 같으면 통과한다 — Unix 5필드와 Quartz 6필드")
    void assert_sameAfterNormalization_passes() {
        assertThatCode(() -> SCHEDULE_9AM.assertMatchesRequest(TriggerType.SCHEDULE, "0 0 9 * * ?"))
            .doesNotThrowAnyException();
        assertThatCode(() -> SCHEDULE_9AM.assertMatchesRequest(null, "0 9 * * *")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("triggerType이 다르면 INVALID_WORKFLOW이고 메시지에 계산값이 있다")
    void assert_triggerTypeMismatch_isRejected() {
        assertInvalidWorkflow(() -> SCHEDULE_9AM.assertMatchesRequest(TriggerType.MANUAL, null), "SCHEDULE");
    }

    @Test
    @DisplayName("cron이 정규화 후에도 다르면 INVALID_WORKFLOW이고 메시지에 노드 cron이 있다")
    void assert_cronMismatch_isRejected() {
        assertInvalidWorkflow(() -> SCHEDULE_9AM.assertMatchesRequest(TriggerType.SCHEDULE, "0 0 10 * * ?"),
            "0 9 * * *");
    }

    @Test
    @DisplayName("계산값이 SCHEDULE이 아닌데 cron을 보내면 INVALID_WORKFLOW")
    void assert_cronOnNonSchedule_isRejected() {
        assertInvalidWorkflow(() -> new TriggerNodeSchedule(TriggerType.WEBHOOK, null)
            .assertMatchesRequest(null, "0 0 9 * * ?"), "WEBHOOK");
        assertInvalidWorkflow(() -> new TriggerNodeSchedule(TriggerType.MANUAL, null)
            .assertMatchesRequest(TriggerType.MANUAL, "0 0 9 * * ?"), "MANUAL");
    }

    @Test
    @DisplayName("SCHEDULE인데 노드에 cron이 없으면 요청 cron은 맞출 대상이 없어 INVALID_WORKFLOW")
    void assert_cronWithoutNodeCron_isRejected() {
        assertInvalidWorkflow(() -> new TriggerNodeSchedule(TriggerType.SCHEDULE, null)
            .assertMatchesRequest(null, "0 0 9 * * ?"), "SCHEDULE");
    }

    @Test
    @DisplayName("빈 문자열 cron은 보내지 않은 것으로 본다")
    void assert_blankCron_isAbsent() {
        assertThatCode(() -> new TriggerNodeSchedule(TriggerType.MANUAL, null).assertMatchesRequest(null, " "))
            .doesNotThrowAnyException();
    }

    // ── assertCronPresent ───────────────────────────────────────────────────

    @Test
    @DisplayName("SCHEDULE인데 노드 cron이 비면 INVALID_CRON_EXPRESSION — 메시지에 노드 id(제어 문자 '?', 64자)")
    void assertCronPresent_scheduleWithoutCron_isRejected() {
        List<Map<String, Object>> nodes = List.of(node("t\n" + "x".repeat(100), "TRIGGER", "schedule.every", Map.of()));

        assertThatThrownBy(() -> TriggerNodeSchedule.compute(nodes, CATALOG).assertCronPresent(nodes))
            .isInstanceOfSatisfying(CustomException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CRON_EXPRESSION);
                assertThat(e.getMessage()).startsWith("schedule.every 노드(t?x")
                    .endsWith(")의 config.cron이 필요합니다").doesNotContain("\n")
                    .doesNotContain("x".repeat(63));
            });
    }

    @Test
    @DisplayName("cron이 있는 SCHEDULE과 SCHEDULE이 아닌 트리거는 통과한다")
    void assertCronPresent_passes() {
        assertThatCode(() -> SCHEDULE_9AM.assertCronPresent(List.of())).doesNotThrowAnyException();
        assertThatCode(() -> new TriggerNodeSchedule(TriggerType.MANUAL, null).assertCronPresent(List.of()))
            .doesNotThrowAnyException();
    }
}
