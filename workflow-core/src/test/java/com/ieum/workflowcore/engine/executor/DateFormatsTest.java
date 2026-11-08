package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DateFormatsTest {

    /** 2026-11-07 13:30 KST 토요일. */
    private static final ZonedDateTime NOW =
        ZonedDateTime.now(Clock.fixed(Instant.parse("2026-11-07T04:30:00Z"), ConditionValues.KST));

    private static Map<String, Object> run(String event, Object... kv) {
        Map<String, Object> in = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            in.put((String) kv[i], kv[i + 1]);
        }
        return Formatter.apply("formatter." + event, in, NOW);
    }

    private static Object out(String event, Object... kv) {
        return run(event, kv).get("output");
    }

    private static void fails(String event, Object... kv) {
        assertThatThrownBy(() -> run(event, kv)).isInstanceOf(FormatterFailure.class);
    }

    @Test
    @DisplayName("date_format: 표기 모양·시간대 바꾸기, 잘못된 패턴·시간대·못 읽는 값은 실패")
    void dateFormat() {
        String t = "2026-11-07T04:30:00Z";
        assertThat(out("date_format", "input", t)).isEqualTo("2026년 11월 7일 (토)");
        assertThat(out("date_format", "input", t, "format", "korean_time")).isEqualTo("2026년 11월 7일 (토) 13:30");
        assertThat(out("date_format", "input", t, "format", "date")).isEqualTo("2026-11-07");
        assertThat(out("date_format", "input", t, "format", "datetime")).isEqualTo("2026-11-07 13:30");
        assertThat(out("date_format", "input", t, "format", "iso")).isEqualTo("2026-11-07T13:30:00+09:00");
        assertThat(out("date_format", "input", t, "format", "custom", "customPattern", "yyyy/MM/dd E"))
            .isEqualTo("2026/11/07 토");
        assertThat(out("date_format", "input", "지금", "format", "korean_time")).isEqualTo("2026년 11월 7일 (토) 13:30");
        assertThat(out("date_format", "input", "2026.11.07", "format", "datetime")).isEqualTo("2026-11-07 00:00");
        assertThat(out("date_format", "input", t, "format", "datetime", "toTimezone", "America/New_York"))
            .isEqualTo("2026-11-06 23:30");
        assertThat(out("date_format", "input", "2026-11-07T04:30", "format", "datetime", "fromTimezone", "UTC"))
            .isEqualTo("2026-11-07 13:30");
        fails("date_format", "input", t, "format", "custom");
        fails("date_format", "input", t, "format", "custom", "customPattern", "y".repeat(101));
        fails("date_format", "input", t, "format", "custom", "customPattern", "yyyy-MM-dd'");
        fails("date_format", "input", t, "toTimezone", "Mars/Base");
        fails("date_format", "input", t, "fromTimezone", "Mars/Base");
        assertThatThrownBy(() -> run("date_format", "input", "다음주"))
            .isInstanceOf(FormatterFailure.class)
            .hasMessage("날짜 표기 바꾸기: '다음주'를 날짜로 읽지 못했어요 (입력: '다음주')");
    }

    @Test
    @DisplayName("date_add: 한국어·영어 단위, 부호 필수, 모르는 단위·빈 식은 실패")
    void dateAdd() {
        String t = "2026-11-07T13:30:00+09:00";
        assertThat(out("date_add", "input", t, "expression", "+3일 -2시간")).isEqualTo("2026-11-10T11:30:00+09:00");
        assertThat(out("date_add", "input", t, "expression", "+1 month -2 days")).isEqualTo("2026-12-05T13:30:00+09:00");
        assertThat(out("date_add", "input", t, "expression", "+2개월")).isEqualTo("2027-01-07T13:30:00+09:00");
        assertThat(out("date_add", "input", t, "expression", "+1달")).isEqualTo("2026-12-07T13:30:00+09:00");
        assertThat(out("date_add", "input", t, "expression", "+1 Year")).isEqualTo("2027-11-07T13:30:00+09:00");
        fails("date_add", "input", t, "expression", "+3 parsecs");
        fails("date_add", "input", t, "expression", "3일");
        fails("date_add", "input", t, "expression", "");
        fails("date_add", "input", t, "expression", "+1일 x");
    }

    @Test
    @DisplayName("date_add_월말: 1/31 +1달 = 2/28")
    void dateAddMonthEnd() {
        assertThat(out("date_add", "input", "2026-01-31", "expression", "+1 month")).isEqualTo("2026-02-28");
    }

    @Test
    @DisplayName("date_add_날짜만: 날짜 단위만이면 날짜, 시각 단위가 섞이면 KST 시각")
    void dateAddDateOnly() {
        assertThat(out("date_add", "input", "2026-11-07", "expression", "+1주")).isEqualTo("2026-11-14");
        assertThat(out("date_add", "input", "2026-11-07", "expression", "+3시간")).isEqualTo("2026-11-07T03:00:00+09:00");
    }

    @Test
    @DisplayName("date_add: 거대 숫자·과한 항 수는 노드 실패(예외가 새지 않음)")
    void dateAddBounds() {
        String t = "2026-11-07T13:30:00+09:00";
        fails("date_add", "input", t, "expression", "+999999999999 years");
        fails("date_add", "input", t, "expression", "+999999 years ".repeat(1000));
        assertThat(out("date_add", "input", t, "expression", "+999999 years ".repeat(20))).isNotNull();
    }

    @Test
    @DisplayName("date_compare: other − input, 버림, 날짜만이면 날짜 단위")
    void dateCompare() {
        String a = "2026-11-07T09:00:00+09:00";
        String b = "2026-11-08T12:30:00+09:00";
        assertThat(run("date_compare", "input", a, "other", b))
            .isEqualTo(Map.of("days", 1L, "hours", 27L, "minutes", 1650L, "isBefore", true));
        assertThat(run("date_compare", "input", b, "other", a))
            .isEqualTo(Map.of("days", -1L, "hours", -27L, "minutes", -1650L, "isBefore", false));
        assertThat(run("date_compare", "input", a, "other", a))
            .isEqualTo(Map.of("days", 0L, "hours", 0L, "minutes", 0L, "isBefore", false));
        assertThat(run("date_compare", "input", "2026-11-07", "other", "2026-11-09T01:00:00+09:00"))
            .isEqualTo(Map.of("days", 2L, "hours", 48L, "minutes", 2880L, "isBefore", true));
        fails("date_compare", "input", a, "other", "");
        fails("date_compare", "input", a);
    }

    @Test
    @DisplayName("빈_입력: 날짜 변환 3개 모두 실패")
    void emptyInput() {
        fails("date_format", "input", "");
        fails("date_add", "input", "", "expression", "+1일");
        fails("date_compare", "input", "", "other", "2026-11-07");
    }
}
