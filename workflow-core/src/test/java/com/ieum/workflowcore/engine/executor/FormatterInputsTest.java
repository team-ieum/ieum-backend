package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FormatterInputsTest {

    @Test
    @DisplayName("JSON 배열 목록은 원소 타입을 보존한다 — 객체는 Map")
    void listKeepsJsonObjects() {
        List<Object> items = FormatterInputs.list("[{\"id\":1,\"t\":\"a\"},\"\",2]");

        assertThat(items).hasSize(3);
        assertThat(items.get(0)).isEqualTo(Map.of("id", 1, "t", "a"));
        assertThat(items.get(1)).isEqualTo("");
        assertThat(items.get(2)).isInstanceOf(Number.class).isEqualTo(2);
    }

    @Test
    @DisplayName("쉼표 목록은 빈 원소도 자리를 지킨다")
    void commaListKeepsEmpty() {
        assertThat(FormatterInputs.list("a,,b")).containsExactly("a", "", "b");
        assertThat(FormatterInputs.list(" a , b ")).containsExactly("a", "b");
        assertThat(FormatterInputs.list(null)).isEmpty();
        assertThat(FormatterInputs.list("")).isEmpty();
    }

    @Test
    @DisplayName("목록이 1만 개를 넘으면 실패")
    void listOverLimitFails() {
        String many = String.join(",", java.util.Collections.nCopies(FormatterInputs.MAX_ITEMS + 1, "a"));

        assertThatThrownBy(() -> FormatterInputs.list(many)).isInstanceOf(FormatterFailure.class);
    }

    @Test
    @DisplayName("텍스트 칸이 10만 자를 넘으면 실패")
    void textOverLimitFails() {
        Map<String, Object> in = Map.of("input", "a".repeat(FormatterInputs.MAX_TEXT + 1));

        assertThatThrownBy(() -> FormatterInputs.text(in, "input"))
            .isInstanceOf(FormatterFailure.class)
            .hasMessage("'input' 칸이 10만 자를 넘어요");
        assertThat(FormatterInputs.text(Map.of(), "input")).isEmpty();
    }

    @Test
    @DisplayName("파국적 정규식은 1초 근처에서 실패한다")
    void timedCatastrophicRegexFails() {
        long start = System.nanoTime();

        // (a+)+$는 JDK 9+ 루프 메모이제이션으로 즉시 끝난다 — 범위 반복 중첩은 메모이제이션을 받지 않아 지수 시간이다.
        assertThatThrownBy(() -> Pattern.compile("(a{1,30}){1,30}$").matcher(FormatterInputs.timed("a".repeat(40) + "!")).find())
            .isInstanceOf(FormatterFailure.class)
            .hasMessage("패턴 검사가 1초를 넘었어요");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3_000);
    }

    @Test
    @DisplayName("요약은 50자에서 자르고 …을 붙인다")
    void summaryCutsAt50() {
        assertThat(FormatterInputs.summary("가".repeat(60))).isEqualTo("가".repeat(50) + "…");
        assertThat(FormatterInputs.summary("0123456789")).isEqualTo("0123456789");
    }
}
