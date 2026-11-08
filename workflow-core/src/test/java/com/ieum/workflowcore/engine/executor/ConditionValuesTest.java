package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConditionValuesTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 5, 10, 0, 0, 0, ConditionValues.KST);

    @Test void textNormalizes() {
        assertThat(ConditionValues.text(null, false)).isEmpty();
        assertThat(ConditionValues.text("  Hello ", false)).isEqualTo("hello");
        assertThat(ConditionValues.text("  Hello ", true)).isEqualTo("Hello");
        assertThat(ConditionValues.text(true, false)).isEqualTo("true");
        assertThat(ConditionValues.text(4.0, true)).isEqualTo("4.0");
    }

    @Test void listItemsSplitsCommasOrReadsJsonArray() {
        assertThat(ConditionValues.listItems("문의, 질문 ,, ", false)).containsExactly("문의", "질문");
        assertThat(ConditionValues.listItems("[\"A\",\" b \",\"\"]", false)).containsExactly("a", "b");
        assertThat(ConditionValues.listItems(List.of("X", 1), false)).containsExactly("x", "1");
        assertThat(ConditionValues.listItems("[not json", false)).containsExactly("[not json");
    }

    @Test void jsonListOnlyForArrays() {
        assertThat(ConditionValues.jsonList("[\"긴급\",\"장애\"]", false)).contains(List.of("긴급", "장애"));
        assertThat(ConditionValues.jsonList("[긴급] 서버 다운", false)).isEmpty();
        assertThat(ConditionValues.jsonList("[1] 공지", false)).isEmpty();          // 뒤 글자 있는 JSON 배열
        assertThat(ConditionValues.jsonList("[\"긴급\"] 서버", false)).isEmpty();
        assertThat(ConditionValues.jsonList("{\"a\":1}", false)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"'1,000원'|1000", "' 15 % '|15", "'-1,234.5'|-1234.5", "'1e3'|1000", "'4.0'|4"})
    void numberReadsHumanFormats(String raw, BigDecimal expected) {
        assertThat(ConditionValues.number(raw)).hasValueSatisfying(n -> assertThat(n).isEqualByComparingTo(expected));
    }

    @Test void numberRejectsUnreadable() {
        assertThat(ConditionValues.number(4)).hasValueSatisfying(n -> assertThat(n).isEqualByComparingTo("4"));
        for (Object bad : new Object[]{"₩1,000", "약 1천", "", "  ", null, true, Double.NaN}) {
            assertThat(ConditionValues.number(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }

    @Test void numberRejectsOver1000CharsAfterCleanup() {
        assertThat(ConditionValues.number("1".repeat(1000) + ",원 ")).isPresent();
        assertThat(ConditionValues.number("1".repeat(1001))).isEmpty();
    }

    @Test void numberStripsUnicodeSpaces() {
        for (String raw : new String[]{"1 000", "1　000"}) {
            assertThat(ConditionValues.number(raw)).as(raw).hasValueSatisfying(n -> assertThat(n).isEqualByComparingTo("1000"));
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "2026-11-05T09:00:00+09:00|2026-11-05T00:00:00Z|false",
        "2026-11-05 09:00:00+09:00|2026-11-05T00:00:00Z|false",
        "2026-11-05T00:00:00.123Z|2026-11-05T00:00:00.123Z|false",
        "2026-11-05T09:00|2026-11-05T00:00:00Z|false",
        "2026-11-05 09:00:30|2026-11-05T00:00:30Z|false",
        "2026-11-05|2026-11-04T15:00:00Z|true",
        "2026.11.05|2026-11-04T15:00:00Z|true",
        "2026/11/05|2026-11-04T15:00:00Z|true",
        "'Thu, 5 Nov 2026 09:00:00 +0900'|2026-11-05T00:00:00Z|false",
        "오늘|2026-11-04T15:00:00Z|true",
        "지금|2026-11-05T01:00:00Z|false"})
    void dateReadsSupportedFormats(String raw, Instant instant, boolean dateOnly) {
        assertThat(ConditionValues.date(raw, NOW)).hasValueSatisfying(w -> {
            assertThat(w.instant()).isEqualTo(instant);
            assertThat(w.dateOnly()).isEqualTo(dateOnly);
        });
    }

    @Test void dateRejectsUnreadable() {
        for (Object bad : new Object[]{"Wed, 5 Nov 2026 09:00:00 +0900", "2026-13-01", "내일", "", null}) {
            assertThat(ConditionValues.date(bad, NOW)).as(String.valueOf(bad)).isEmpty();
        }
    }

    @Test void koreanDate() {
        assertThat(ConditionValues.date("2026년 11월 7일", NOW)).contains(ConditionValues.When.on(LocalDate.of(2026, 11, 7)));
        assertThat(ConditionValues.date("2026년11월7일", NOW)).contains(ConditionValues.When.on(LocalDate.of(2026, 11, 7)));
        assertThat(ConditionValues.date("2026년 13월 1일", NOW)).isEmpty();
        assertThat(ConditionValues.date("2026년 11월 7일 13:30", NOW)).isEmpty();
    }

    @Test void unixTime() {
        Instant t = Instant.ofEpochSecond(1794027000);
        assertThat(ConditionValues.date("1794027000", NOW)).contains(ConditionValues.When.at(t));
        assertThat(ConditionValues.date("1794027000000", NOW)).contains(ConditionValues.When.at(t));
        for (String bad : new String[]{"12345", "17940270000", "-1794027000"}) {
            assertThat(ConditionValues.date(bad, NOW)).as(bad).isEmpty();
        }
    }

    @Test void zoneArgument() {
        assertThat(ConditionValues.date("2026-11-07T09:00", NOW, java.time.ZoneId.of("UTC")).orElseThrow().instant())
            .isEqualTo(Instant.parse("2026-11-07T09:00:00Z"));
    }

    @Test void utcInstantMapsToKstDay() {
        assertThat(ConditionValues.date("2026-11-04T16:00:00Z", NOW).orElseThrow().day())
            .isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test void compareUsesDayWhenEitherIsDateOnly() {
        var day = ConditionValues.date("2026-11-05", NOW).orElseThrow();
        var nineAm = ConditionValues.date("2026-11-05T09:00:00+09:00", NOW).orElseThrow();
        var tenAm = ConditionValues.date("2026-11-05T10:00:00+09:00", NOW).orElseThrow();
        assertThat(ConditionValues.compare(nineAm, day)).isZero();
        assertThat(ConditionValues.compare(nineAm, tenAm)).isNegative();
    }

    @Test void boolReadsKoreanAndEnglish() {
        for (Object yes : new Object[]{true, "TRUE", " yes ", "y", "예", "1", 1}) {
            assertThat(ConditionValues.bool(yes)).as(String.valueOf(yes)).isTrue();
        }
        for (Object no : new Object[]{false, "No", "n", "아니오", "0", 0}) {
            assertThat(ConditionValues.bool(no)).as(String.valueOf(no)).isFalse();
        }
        for (Object unknown : new Object[]{"maybe", "", null}) {
            assertThat(ConditionValues.bool(unknown)).as(String.valueOf(unknown)).isNull();
        }
    }

    @Test void isEmptyCoversRenderedJson() {
        for (Object empty : new Object[]{null, "", "   ", "[]", " {} ", List.of(), Map.of()}) {
            assertThat(ConditionValues.isEmpty(empty)).as(String.valueOf(empty)).isTrue();
        }
        for (Object full : new Object[]{"0", "[1]", List.of(""), false}) {
            assertThat(ConditionValues.isEmpty(full)).as(String.valueOf(full)).isFalse();
        }
    }
}
