package com.ieum.workflowcore.engine.executor;

import static com.ieum.workflowcore.engine.executor.FormatterInputs.choice;
import static com.ieum.workflowcore.engine.executor.FormatterInputs.json;
import static com.ieum.workflowcore.engine.executor.FormatterInputs.list;
import static com.ieum.workflowcore.engine.executor.FormatterInputs.positiveInt;
import static com.ieum.workflowcore.engine.executor.FormatterInputs.text;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 유틸 포맷터 3개 — 대응표·목록 고르기·목록을 글로. */
final class ListFormats {

    private ListFormats() {
    }

    /** 키는 글자 그대로(앞뒤 공백만 제거). 값은 문자열이면 그대로, 그 밖은 JSON 글자, null은 {@code ""}. */
    static Map<String, Object> lookupTable(Map<String, Object> in, ZonedDateTime now) {
        if (!(in.get("table") instanceof Map<?, ?> table)) {
            throw new FormatterFailure("'대응표' 칸이 대응표(키-값) 모양이 아니에요");
        }
        if (table.size() > FormatterInputs.MAX_ITEMS) {
            throw new FormatterFailure("대응표가 1만 개를 넘어요");
        }
        String key = text(in, "input").strip();
        if (key.isEmpty()) {
            return Map.of("output", "");
        }
        if (!table.containsKey(key)) {
            return Map.of("output", text(in, "fallback"));
        }
        Object value = table.get(key);
        return Map.of("output", value == null ? "" : json(value));
    }

    /** 고른 원소를 그대로(객체면 Map). JSON null 원소는 null 출력. */
    static Map<String, Object> pick(Map<String, Object> in, ZonedDateTime now) {
        List<Object> items = items(in);
        if (items.isEmpty()) {
            throw new FormatterFailure("목록이 비어 있어요");
        }
        int i = switch (choice(in, "position", "first", Set.of("first", "last", "nth"))) {
            case "first" -> 0;
            case "last" -> items.size() - 1;
            default -> positiveInt(in, "index", "몇 번째") - 1;
        };
        if (i >= items.size()) {
            throw new FormatterFailure((i + 1) + "번째 항목이 없어요(목록 " + items.size() + "개)");
        }
        Map<String, Object> out = new HashMap<>();
        out.put("output", items.get(i));
        return out;
    }

    /** 결과 길이를 이어 붙이기 전에 계산해 10만 자를 넘으면 실패(1만 자 구분 기호 × 1만 개 = 1억 자 방지). null 원소는 {@code ""}. */
    static Map<String, Object> toText(Map<String, Object> in, ZonedDateTime now) {
        String text = text(in, "separator");
        String sep = text.isEmpty() ? ", " : text;
        List<String> parts = items(in).stream().map(v -> v == null ? "" : json(v)).toList();
        long total = (long) sep.length() * Math.max(0, parts.size() - 1);
        for (String part : parts) {
            total += part.length();
        }
        if (total > FormatterInputs.MAX_TEXT) {
            throw new FormatterFailure("결과가 10만 자를 넘어요");
        }
        return Map.of("output", String.join(sep, parts));
    }

    /** 문자열 입력은 10만 자 제한을 거친다. */
    private static List<Object> items(Map<String, Object> in) {
        Object value = in.get("input");
        return list(value instanceof String ? text(in, "input") : value);
    }
}
