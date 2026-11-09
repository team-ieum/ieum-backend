package com.ieum.workflowcore.engine.executor;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 포맷터 공용 입력 읽기·제한. 실패는 전부 {@link FormatterFailure}(이유만). */
final class FormatterInputs {

    static final int MAX_TEXT = 100_000;
    static final int MAX_ITEMS = 10_000;
    static final int MAX_PATTERN = 200;
    static final int MAX_CUSTOM_PATTERN = 100;
    static final Duration REGEX_TIMEOUT = Duration.ofSeconds(1);

    private static final int SUMMARY_LENGTH = 50;

    /**
     * 뒤 글자를 거절해야 {@code "[2026] 보고서"}가 배열로 읽히지 않는다. 소수는 BigDecimal로 읽고 plain으로 써서
     * {@code 12345678.9}가 {@code 1.23456789E7}이 되지 않는다. 스레드 안전하다.
     */
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private FormatterInputs() {
    }

    /** null → {@code ""}, Map·List는 JSON, 그 밖은 글자로. 10만 자 초과는 실패. */
    static String text(Map<String, Object> in, String key) {
        Object value = in.get(key);
        String s = value == null ? "" : json(value);
        if (s.length() > MAX_TEXT) {
            throw new FormatterFailure("'" + key + "' 칸이 10만 자를 넘어요");
        }
        return s;
    }

    static String required(Map<String, Object> in, String key, String fieldTitle) {
        String s = text(in, key);
        if (s.isEmpty()) {
            throw new FormatterFailure("'" + fieldTitle + "' 칸이 비어 있어요");
        }
        return s;
    }

    /** 없거나 빈 값이면 기본값(기본값이 null이면 필수 선택 칸이라 실패), 허용 목록 밖이면 실패. */
    static String choice(Map<String, Object> in, String key, String dflt, Set<String> allowed) {
        Object value = in.get(key);
        if (value == null || "".equals(value)) {
            if (dflt == null) {
                throw new FormatterFailure("'" + key + "' 칸을 골라 주세요");
            }
            return dflt;
        }
        String s = String.valueOf(value);
        if (!allowed.contains(s)) {
            throw new FormatterFailure("'" + key + "' 값을 알 수 없어요: " + summary(s));
        }
        return s;
    }

    static boolean flag(Map<String, Object> in, String key) {
        return Boolean.TRUE.equals(ConditionValues.bool(in.get(key)));
    }

    static int positiveInt(Map<String, Object> in, String key, String fieldTitle) {
        try {
            int n = new BigDecimal(text(in, key).strip()).intValueExact();
            if (n >= 1) {
                return n;
            }
        } catch (NumberFormatException | ArithmeticException ignored) {
            // 아래 실패로
        }
        throw new FormatterFailure("'" + fieldTitle + "' 칸은 1 이상의 정수여야 해요");
    }

    /**
     * spec "목록 읽기": List는 그대로, {@code [}로 시작하는 JSON 배열은 그 배열(원소 타입 보존 — 객체는 Map),
     * 아니면 쉼표로 나눠 앞뒤 공백 제거. 빈 원소도 자리를 지킨다. null·공백뿐인 문자열 → 빈 목록.
     */
    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        List<Object> items;
        if (value == null || value instanceof String blank && blank.isBlank()) {
            items = List.of();
        } else if (value instanceof List<?> l) {
            items = (List<Object>) l;
        } else {
            String s = String.valueOf(value);
            items = s.strip().startsWith("[") ? jsonArray(s.strip()) : null;
            if (items == null) {
                items = new ArrayList<>();
                for (String part : s.split(",", -1)) {
                    items.add(part.strip());
                }
            }
        }
        if (items.size() > MAX_ITEMS) {
            throw new FormatterFailure("목록이 1만 개를 넘어요");
        }
        return items;
    }

    private static List<Object> jsonArray(String s) {
        try {
            return JSON.readValue(s, List.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** JSON 객체 문자열 → Map, 읽지 못하면 null. 10만 자 초과는 실패. */
    static Map<?, ?> jsonObject(String s) {
        if (s.length() > MAX_TEXT) {
            throw new FormatterFailure("'table' 칸이 10만 자를 넘어요");
        }
        try {
            return JSON.readValue(s, Map.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 문자열은 그대로, BigDecimal은 plain 글자, 그 밖은 JSON. */
    static String json(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof BigDecimal d) {
            return d.toPlainString();
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new FormatterFailure("값을 JSON으로 바꾸지 못했어요");
        }
    }

    /** 정규식 입력을 감싼다 — 마감({@link #REGEX_TIMEOUT})을 넘겨 글자를 읽으면 실패. */
    static CharSequence timed(CharSequence s) {
        return new TimedCharSequence(s, System.nanoTime() + REGEX_TIMEOUT.toNanos());
    }

    /**
     * 정규식 매칭을 감싼다. Java 정규식은 그룹 반복마다 재귀라 {@code (a|b)*} 같은 패턴에 긴 입력이면 StackOverflowError가 나는데,
     * Error는 실행기의 catch(Exception)를 지나 워커 스레드까지 올라가므로 여기서 노드 실패로 바꾼다.
     */
    static <T> T regex(Supplier<T> work) {
        try {
            return work.get();
        } catch (StackOverflowError e) {
            throw new FormatterFailure("패턴이 너무 복잡해요");
        }
    }

    /** 실패 메시지용 입력 요약 — 앞 50자 + {@code …}(서로게이트 쌍은 가르지 않는다). */
    static String summary(Object input) {
        String s = input == null ? "" : json(input);
        if (s.length() <= SUMMARY_LENGTH) {
            return s;
        }
        int end = Character.isHighSurrogate(s.charAt(SUMMARY_LENGTH - 1)) ? SUMMARY_LENGTH - 1 : SUMMARY_LENGTH;
        return s.substring(0, end) + "…";
    }

    record TimedCharSequence(CharSequence s, long deadline) implements CharSequence {

        @Override
        public char charAt(int index) {
            if (System.nanoTime() - deadline > 0) {
                throw new FormatterFailure("패턴 검사가 1초를 넘었어요");
            }
            return s.charAt(index);
        }

        @Override
        public int length() {
            return s.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new TimedCharSequence(s.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return s.toString();
        }
    }
}
