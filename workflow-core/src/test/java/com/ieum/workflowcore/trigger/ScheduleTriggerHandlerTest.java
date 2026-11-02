package com.ieum.workflowcore.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.common.exception.CustomException;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.Node;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 새 스케줄 노드(칸 → cron)의 실행 출력 — cron은 schedule.every에서만 낸다. */
class ScheduleTriggerHandlerTest {

    private static Map<String, Object> output(String event, Map<String, Object> config) {
        return new ScheduleTriggerHandler().output(
            new Node("t1", NodeType.TRIGGER, "트리거", config, event), Map.of(), Map.of());
    }

    @Test
    @DisplayName("schedule.weekly — 출력은 triggeredAt뿐(cron 없음)")
    void weekly_outputsOnlyTriggeredAt() {
        assertThat(output("schedule.weekly", new HashMap<>(Map.of("weekday", "FRI", "hour", "17"))))
            .containsOnlyKeys("triggeredAt");
    }

    @Test
    @DisplayName("schedule.every — triggeredAt과 cron 원문")
    void every_outputsCronRaw() {
        assertThat(output("schedule.every", new HashMap<>(Map.of("cron", "0 9 * * *"))))
            .containsOnlyKeys("triggeredAt", "cron").containsEntry("cron", "0 9 * * *");
    }

    @Test
    @DisplayName("schedule.daily 칸이 비거나 config가 없으면 CustomException(시각)")
    void dailyWithoutHour_throws() {
        assertThatThrownBy(() -> output("schedule.daily", new HashMap<>()))
            .isInstanceOf(CustomException.class).hasMessageContaining("시각");
        assertThatThrownBy(() -> output("schedule.daily", null))
            .isInstanceOf(CustomException.class).hasMessageContaining("시각");
    }
}
