package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TRIGGER 노드 config → 워크플로우 트리거 타입·cron 추출 규칙. REST 생성·수정과 AI 저장이 같은 규칙을 쓴다. */
class TriggerNodeScheduleTest {

    private static Map<String, Object> node(String type, Object config) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", "n-" + type);
        node.put("type", type);
        node.put("config", config);
        return node;
    }

    private static Map<String, Object> trigger(Map<String, Object> config) {
        return node("TRIGGER", config);
    }

    @Test
    @DisplayName("SCHEDULE TRIGGER 노드면 triggerType과 cron을 함께 읽는다")
    void schedule_readsTriggerTypeAndCron() {
        Optional<TriggerNodeSchedule> found = TriggerNodeSchedule.find(List.of(
            node("HTTP", new HashMap<>()),
            trigger(Map.of("triggerType", "SCHEDULE", "cron", "0 9 * * *"))));

        assertThat(found).contains(new TriggerNodeSchedule(TriggerType.SCHEDULE, "0 9 * * *"));
    }

    @Test
    @DisplayName("cron이 없거나 문자열이 아니면 cron은 null이다 — 요청 최상위 값으로 채우지 않는다")
    void missingOrNonStringCron_isNull() {
        assertThat(TriggerNodeSchedule.find(List.of(trigger(Map.of("triggerType", "WEBHOOK")))))
            .contains(new TriggerNodeSchedule(TriggerType.WEBHOOK, null));
        assertThat(TriggerNodeSchedule.find(List.of(trigger(Map.of("triggerType", "SCHEDULE", "cron", 900)))))
            .contains(new TriggerNodeSchedule(TriggerType.SCHEDULE, null));
    }

    @Test
    @DisplayName("TRIGGER 노드가 없거나 triggerType이 없으면 empty — 호출부가 요청 최상위 값을 쓴다")
    void noDeclaration_isEmpty() {
        assertThat(TriggerNodeSchedule.find(List.of(node("AI", Map.of("prompt", "p"))))).isEmpty();
        assertThat(TriggerNodeSchedule.find(List.of(trigger(Map.of("cron", "0 9 * * *"))))).isEmpty();
        assertThat(TriggerNodeSchedule.find(List.of(trigger(null)))).isEmpty();
        assertThat(TriggerNodeSchedule.find(List.of(node("TRIGGER", "문자열 config")))).isEmpty();
    }

    @Test
    @DisplayName("triggerType을 선언한 TRIGGER 노드가 여럿이면 마지막 것을 쓴다")
    void multipleTriggers_lastWins() {
        assertThat(TriggerNodeSchedule.find(List.of(
            trigger(Map.of("triggerType", "WEBHOOK")),
            trigger(Map.of("triggerType", "SCHEDULE", "cron", "0 9 * * *")))))
            .contains(new TriggerNodeSchedule(TriggerType.SCHEDULE, "0 9 * * *"));
    }

    @Test
    @DisplayName("TRIGGER가 아닌 노드의 config.triggerType은 무시한다")
    void nonTriggerNodes_areIgnored() {
        assertThat(TriggerNodeSchedule.find(List.of(
            node("HTTP", Map.of("triggerType", "SCHEDULE", "cron", "0 9 * * *"))))).isEmpty();
    }

    @Test
    @DisplayName("알 수 없는 triggerType이면 INVALID_WORKFLOW — REST가 잘못된 enum을 400으로 거부하는 것과 같다")
    void unknownTriggerType_isInvalidWorkflow() {
        assertThatThrownBy(() -> TriggerNodeSchedule.find(List.of(trigger(Map.of("triggerType", "DAILY")))))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW));
    }
}
