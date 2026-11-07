package com.ieum.workflowcore.engine.executor;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.function.BiFunction;

/** {@code formatter.*} 이벤트 → (title, 변환) 표. 변환은 런타임이 넘긴 {@code input}만 읽는다. */
final class Formatter {

    private record Op(String title, BiFunction<Map<String, Object>, ZonedDateTime, Map<String, Object>> fn) {
    }

    private static final Map<String, Op> OPS = Map.of(
        "formatter.text_trim", new Op("공백 정리",
            (in, now) -> Map.of("output", FormatterInputs.text(in, "input").strip())));

    private Formatter() {
    }

    static boolean supports(String event) {
        return OPS.containsKey(event);
    }

    static String title(String event) {
        Op op = OPS.get(event);
        return op == null ? null : op.title();
    }

    /** 실패는 {@code "<title>: <이유> (입력: '<요약>')"} 메시지의 {@link FormatterFailure}. */
    static Map<String, Object> apply(String event, Map<String, Object> input, ZonedDateTime now) {
        Op op = OPS.get(event);
        if (op == null) {
            throw new FormatterFailure("지원하지 않는 포맷터예요: " + event);
        }
        try {
            return op.fn().apply(input, now);
        } catch (FormatterFailure e) {
            throw new FormatterFailure(op.title() + ": " + e.getMessage()
                + " (입력: '" + FormatterInputs.summary(input.get("input")) + "')");
        }
    }
}
