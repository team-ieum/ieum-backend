package com.ieum.workflowcore.engine.executor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 조건 분기의 값 읽기 규칙(텍스트·목록·숫자·날짜·참거짓·비어 있음).
 * 판단 불가는 empty/null로 돌려주고 경고·거짓 처리는 실행기 몫이다.
 */
final class ConditionValues {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 뒤 글자를 거절해야 {@code "[2026] 보고서"}가 {@code [2026]}으로 읽히지 않는다. 스레드 안전하다. */
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** {@code day}는 KST 날짜. 날짜만 읽힌 값은 {@code instant} = 그날 KST 00:00. */
    record When(Instant instant, LocalDate day, boolean dateOnly) {

        static When at(Instant instant) {
            return new When(instant, LocalDate.ofInstant(instant, KST), false);
        }

        static When on(LocalDate day) {
            return new When(day.atStartOfDay(KST).toInstant(), day, true);
        }
    }

    private ConditionValues() {
    }

    static String text(Object value, boolean caseSensitive) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value).strip();
        return caseSensitive ? s : s.toLowerCase(Locale.ROOT);
    }

    static Optional<List<String>> jsonList(Object value, boolean caseSensitive) {
        List<?> items;
        if (value instanceof List<?> list) {
            items = list;
        } else if (value instanceof String s && s.strip().startsWith("[")) {
            try {
                items = JSON.readValue(s.strip(), List.class);
            } catch (Exception e) {
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }
        return Optional.of(items.stream()
            .map(item -> text(item, caseSensitive))
            .filter(item -> !item.isEmpty())
            .toList());
    }

    static List<String> listItems(Object value, boolean caseSensitive) {
        return jsonList(value, caseSensitive).orElseGet(() -> Arrays.stream(text(value, caseSensitive).split(","))
            .map(String::strip)
            .filter(item -> !item.isEmpty())
            .toList());
    }

    static Optional<BigDecimal> number(Object value) {
        String s;
        if (value instanceof Number n) {
            s = n.toString();
        } else if (value instanceof String str) {
            s = str.replaceAll("[\\s,원%]", "");
        } else {
            return Optional.empty();
        }
        if (s.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(s));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static Optional<When> date(Object value, ZonedDateTime now) {
        if (value == null) {
            return Optional.empty();
        }
        String s = String.valueOf(value).strip();
        if (s.isEmpty()) {
            return Optional.empty();
        }
        if (s.equals("오늘")) {
            return Optional.of(When.on(now.withZoneSameInstant(KST).toLocalDate()));
        }
        if (s.equals("지금")) {
            return Optional.of(When.at(now.toInstant()));
        }
        try {
            return Optional.of(When.at(OffsetDateTime.parse(s).toInstant()));
        } catch (DateTimeParseException ignored) {
            // 다음 형식
        }
        try {
            String t = s.length() > 10 && s.charAt(10) == ' ' ? s.substring(0, 10) + 'T' + s.substring(11) : s;
            return Optional.of(When.at(LocalDateTime.parse(t).atZone(KST).toInstant()));
        } catch (DateTimeParseException ignored) {
            // 다음 형식
        }
        try {
            return Optional.of(When.on(LocalDate.parse(s.replace('.', '-').replace('/', '-'))));
        } catch (DateTimeParseException ignored) {
            // 다음 형식
        }
        try {
            return Optional.of(When.at(ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    static int compare(When a, When b) {
        return a.dateOnly() || b.dateOnly() ? a.day().compareTo(b.day()) : a.instant().compareTo(b.instant());
    }

    static Boolean bool(Object value) {
        return switch (text(value, false)) {
            case "true", "yes", "y", "예", "1" -> Boolean.TRUE;
            case "false", "no", "n", "아니오", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Collection<?> c) {
            return c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return m.isEmpty();
        }
        String s = String.valueOf(value).strip();
        return s.isEmpty() || s.equals("[]") || s.equals("{}");
    }
}
