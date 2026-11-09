package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.workflowcore.engine.ExecutionContext;
import com.ieum.workflowcore.engine.ExecutionCursor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ListFormatsTest {

    private static final ZonedDateTime NOW =
        ZonedDateTime.now(Clock.fixed(Instant.parse("2026-11-07T04:30:00Z"), ConditionValues.KST));

    private static final Map<String, Object> TABLE = Map.of("urgent", "긴급", "Low", "낮음");
    private static final String ITEMS = "[{\"id\":1,\"title\":\"A\"},{\"id\":2,\"title\":\"B\"}]";

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
        assertThatThrownBy(() -> out(event, kv)).isInstanceOf(FormatterFailure.class);
    }

    @Test
    @DisplayName("lookup_table: 글자 그대로(대소문자 구분, 앞뒤 공백만 제거), 없으면 fallback·\"\", 빈 입력은 \"\"")
    void lookupTable() {
        assertThat(out("lookup_table", "input", "urgent", "table", TABLE)).isEqualTo("긴급");
        assertThat(out("lookup_table", "input", " urgent ", "table", TABLE)).isEqualTo("긴급");
        assertThat(out("lookup_table", "input", "URGENT", "table", TABLE, "fallback", "x")).isEqualTo("x");
        assertThat(out("lookup_table", "input", "URGENT", "table", TABLE)).isEqualTo("");
        assertThat(out("lookup_table", "input", "", "table", TABLE, "fallback", "x")).isEqualTo("");
        fails("lookup_table", "input", "urgent", "table", "urgent=긴급");
        fails("lookup_table", "input", "urgent");
        Map<String, Object> big = new HashMap<>();
        for (int i = 0; i <= FormatterInputs.MAX_ITEMS; i++) {
            big.put("k" + i, "v");
        }
        fails("lookup_table", "input", "k1", "table", big);
    }

    @Test
    @DisplayName("lookup_table: 문자열 아닌 값은 글자로 — 숫자 \"3\", 객체 JSON, null은 \"\"")
    void lookupTableNonStringValues() {
        Map<String, Object> table = new HashMap<>();
        table.put("n", 3);
        table.put("o", Map.of("k", 1));
        table.put("z", null);
        assertThat(out("lookup_table", "input", "n", "table", table)).isEqualTo("3");
        assertThat(out("lookup_table", "input", "o", "table", table)).isEqualTo("{\"k\":1}");
        assertThat(out("lookup_table", "input", "z", "table", table, "fallback", "x")).isEqualTo("");
    }

    @Test
    @DisplayName("list_pick_객체_원소는_Map_그대로 — 다음 노드엔 JSON 문자열로 간다")
    void listPickKeepsMap() {
        assertThat(out("list_pick", "input", ITEMS)).isEqualTo(Map.of("id", 1, "title", "A"));
        assertThat(out("list_pick", "input", ITEMS, "position", "last")).isEqualTo(Map.of("id", 2, "title", "B"));
        assertThat(out("list_pick", "input", ITEMS, "position", "nth", "index", 2))
            .isEqualTo(Map.of("id", 2, "title", "B"));

        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setContext(new ExecutionContext());
        cursor.getContext().setNodeOutput("f", run("list_pick", "input", ITEMS));
        assertThat(cursor.renderVariables("{{nodes.f.output.output}}")).isEqualTo("{\"id\":1,\"title\":\"A\"}");
    }

    @Test
    @DisplayName("list_pick: 빈 원소도 자리 유지, 범위 밖·index 없음·빈 목록은 실패, List 입력은 그대로")
    void listPick() {
        assertThat(out("list_pick", "input", "a,,b", "position", "nth", "index", 2)).isEqualTo("");
        fails("list_pick", "input", "a,,b", "position", "nth", "index", 4);
        fails("list_pick", "input", "a,,b", "position", "nth");
        fails("list_pick", "input", "");
        assertThat(out("list_pick", "input", List.of("x", "y"), "position", "last")).isEqualTo("y");
    }

    @Test
    @DisplayName("list_to_text: 문자열 그대로·객체 JSON·숫자/참거짓 글자, 기본 구분 \", \", 빈 입력 \"\"")
    void listToText() {
        assertThat(out("list_to_text", "input", "[\"a\",{\"k\":1},3,true]")).isEqualTo("a, {\"k\":1}, 3, true");
        assertThat(out("list_to_text", "input", "a,b", "separator", " / ")).isEqualTo("a / b");
        assertThat(out("list_to_text", "input", "a,b")).isEqualTo("a, b");
        assertThat(out("list_to_text", "input", "")).isEqualTo("");
        assertThat(out("list_to_text", "input", "[\"a\",null,\"b\"]")).isEqualTo("a, , b");
        assertThat(out("list_to_text", "input", "a,b", "separator", "")).isEqualTo("a, b");
    }

    @Test
    @DisplayName("검색 items 모양(중첩 객체·배열·숫자·null)을 고르면 다음 노드 참조가 같은 JSON이다")
    void listPickSearchItemsRoundTrip() {
        String items = "[{\"id\":7,\"meta\":{\"tags\":[\"a\",\"b\"],\"score\":2.5,\"note\":null}},3]";
        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setContext(new ExecutionContext());
        cursor.getContext().setNodeOutput("f", run("list_pick", "input", items));
        assertThat(cursor.renderVariables("{{nodes.f.output.output}}"))
            .isEqualTo("{\"id\":7,\"meta\":{\"tags\":[\"a\",\"b\"],\"score\":2.5,\"note\":null}}");
        assertThat(out("list_pick", "input", items, "position", "last")).isEqualTo(3);
    }

    @Test
    @DisplayName("list_pick: null 원소는 null 그대로, 공백뿐인 입력은 빈 목록이라 실패")
    void listPickNullAndBlank() {
        Map<String, Object> picked = run("list_pick", "input", "[null,1]");
        assertThat(picked).containsKey("output");
        assertThat(picked.get("output")).isNull();
        fails("list_pick", "input", "   ");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("list_to_text: 결과가 10만 자를 넘으면 이어 붙이기 전에 실패(1만 개 × 큰 구분 기호, List 입력도)")
    void listToTextResultLimit() {
        String many = ",".repeat(FormatterInputs.MAX_ITEMS - 1);
        fails("list_to_text", "input", many, "separator", "x".repeat(FormatterInputs.MAX_TEXT));
        fails("list_to_text", "input", many, "separator", "xxxxxxxxxxx");
        fails("list_to_text", "input", "a", "separator", "x".repeat(FormatterInputs.MAX_TEXT + 1));
        fails("list_to_text", "input", List.of("y".repeat(60_000), "z".repeat(60_000)));
        assertThat(out("list_to_text", "input", many, "separator", "x".repeat(10))).asString().hasSize(99_990);
    }

    @Test
    @DisplayName("목록 속 소수는 지수 표기 없이·정밀도 그대로 글자로 나간다")
    void decimalsStayPlain() {
        assertThat(out("list_to_text", "input", "[12345678.9, 0.0001, 100000000000000000000.5]"))
            .isEqualTo("12345678.9, 0.0001, 100000000000000000000.5");
        assertThat(out("list_pick", "input", "[12345678.9]")).isEqualTo("12345678.9");
        assertThat(out("list_pick", "input", "[0.0001]")).isEqualTo("0.0001");
        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setContext(new ExecutionContext());
        cursor.getContext().setNodeOutput("f", run("list_pick", "input", "[{\"price\":12345678.9}]"));
        assertThat(cursor.renderVariables("{{nodes.f.output.output}}")).isEqualTo("{\"price\":12345678.9}");
    }

    @Test
    @DisplayName("lookup_table: 참조식으로 치환된 JSON 객체 문자열 대응표를 읽고, 객체가 아니면 실패")
    void lookupTableJsonString() {
        assertThat(out("lookup_table", "table", "{\"x\":\"y\"}", "input", "x")).isEqualTo("y");
        fails("lookup_table", "table", "[1]", "input", "x");
        fails("lookup_table", "table", "{broken", "input", "x");
    }
}
