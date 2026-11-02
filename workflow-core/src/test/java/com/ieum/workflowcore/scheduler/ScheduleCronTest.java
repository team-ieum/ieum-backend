package com.ieum.workflowcore.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.quartz.CronExpression;

class ScheduleCronTest {

    static Stream<Arguments> valid() {
        return Stream.of(
            Arguments.of("schedule.hourly", Map.of(), "0 0 * * * ?"),
            Arguments.of("schedule.hourly", Map.of("weekends", false), "0 0 * ? * MON-FRI"),
            Arguments.of("schedule.hourly", Map.of("weekends", "false"), "0 0 * ? * MON-FRI"),
            Arguments.of("schedule.hourly", Map.of("weekends", true), "0 0 * * * ?"),
            Arguments.of("schedule.daily", Map.of("hour", "9"), "0 0 9 * * ?"),
            Arguments.of("schedule.daily", Map.of("hour", 9, "weekends", false), "0 0 9 ? * MON-FRI"),
            Arguments.of("schedule.weekly", Map.of("weekday", "FRI", "hour", "17"), "0 0 17 ? * FRI"),
            Arguments.of("schedule.monthly", Map.of("day", "L", "hour", "0"), "0 0 0 L * ?"),
            Arguments.of("schedule.monthly", Map.of("day", "15", "hour", "23"), "0 0 23 15 * ?"),
            Arguments.of("schedule.monthly", Map.of("day", 1, "hour", 0), "0 0 0 1 * ?"));
    }

    @ParameterizedTest(name = "{0} {1} → [{2}]")
    @MethodSource("valid")
    @DisplayName("새 스케줄 event는 칸 값으로 Quartz cron을 만든다")
    void of_newEvents(String event, Map<String, Object> config, String expected) {
        String cron = ScheduleCron.of("n", event, config);
        assertThat(cron).isEqualTo(expected);
        assertThat(CronExpression.isValidExpression(cron)).isTrue();
    }

    @Test
    @DisplayName("schedule.every는 config.cron 문자열을 그대로, 없으면 null")
    void of_every() {
        assertThat(ScheduleCron.of("n", "schedule.every", Map.of("cron", "0 9 * * *"))).isEqualTo("0 9 * * *");
        assertThat(ScheduleCron.of("n", "schedule.every", Map.of())).isNull();
        assertThat(ScheduleCron.of("n", "schedule.every", Map.of("cron", 5))).isNull();
        assertThat(ScheduleCron.of("n", "webhook.catch", Map.of())).isNull();
    }

    static Stream<Arguments> invalid() {
        Map<String, Object> nullHour = new HashMap<>();
        nullHour.put("hour", null);
        return Stream.of(
            Arguments.of("schedule.daily", Map.of(), "시각"),
            Arguments.of("schedule.daily", nullHour, "시각"),
            Arguments.of("schedule.daily", Map.of("hour", "24"), "시각"),
            Arguments.of("schedule.daily", Map.of("hour", "-1"), "시각"),
            Arguments.of("schedule.daily", Map.of("hour", "9시"), "시각"),
            Arguments.of("schedule.weekly", Map.of("weekday", "FRIDAY", "hour", "9"), "요일"),
            Arguments.of("schedule.weekly", Map.of("hour", "9"), "요일"),
            Arguments.of("schedule.weekly", Map.of("weekday", "FRI"), "시각"),
            Arguments.of("schedule.monthly", Map.of("day", "29", "hour", "9"), "날짜"),
            Arguments.of("schedule.monthly", Map.of("day", "0", "hour", "9"), "날짜"),
            Arguments.of("schedule.monthly", Map.of("hour", "9"), "날짜"));
    }

    @ParameterizedTest(name = "{0} {1} → {2} 거절")
    @MethodSource("invalid")
    @DisplayName("칸이 없거나 범위 밖이면 INVALID_CRON_EXPRESSION, 메시지에 노드 id와 칸 이름")
    void of_invalid(String event, Map<String, Object> config, String field) {
        assertThatThrownBy(() -> ScheduleCron.of("node-1", event, config))
            .isInstanceOfSatisfying(CustomException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CRON_EXPRESSION);
                assertThat(e.getMessage()).contains("node-1").contains(field).doesNotContain("*");
            });
    }

    @Test
    @DisplayName("메시지는 '<제목> 노드(<id>)의 <칸>을 골라 주세요' 모양")
    void of_invalid_messageFormat() {
        assertThatThrownBy(() -> ScheduleCron.of("n", "schedule.daily", Map.of()))
            .hasMessage("매일 노드(n)의 시각을 골라 주세요");
    }
}
