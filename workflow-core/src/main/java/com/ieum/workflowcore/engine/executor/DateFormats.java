package com.ieum.workflowcore.engine.executor;

import static com.ieum.workflowcore.engine.executor.ConditionValues.KST;
import static com.ieum.workflowcore.engine.executor.FormatterInputs.choice;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 날짜 포맷터 3개. 입력은 {@link ConditionValues#date}로 읽는다({@code 지금}·{@code 오늘} 포함). */
final class DateFormats {

    /** 식 한 항: 부호 필수 + 숫자 + 단위(붙어도 됨). */
    private static final Pattern TERM = Pattern.compile("\\s*([+-])(\\d+)\\s*(\\p{L}+)\\s*");

    /** 항 수·숫자 자릿수 상한 — 20항 × 999999년도 {@code java.time} 범위(±10억 년) 안이라 연산이 넘치지 않는다. */
    private static final int MAX_TERMS = 20;
    private static final int MAX_AMOUNT_DIGITS = 6;

    private static final Map<String, ChronoUnit> UNITS = Map.ofEntries(
        Map.entry("year", ChronoUnit.YEARS), Map.entry("years", ChronoUnit.YEARS), Map.entry("년", ChronoUnit.YEARS),
        Map.entry("month", ChronoUnit.MONTHS), Map.entry("months", ChronoUnit.MONTHS),
        Map.entry("개월", ChronoUnit.MONTHS), Map.entry("달", ChronoUnit.MONTHS),
        Map.entry("week", ChronoUnit.WEEKS), Map.entry("weeks", ChronoUnit.WEEKS), Map.entry("주", ChronoUnit.WEEKS),
        Map.entry("day", ChronoUnit.DAYS), Map.entry("days", ChronoUnit.DAYS), Map.entry("일", ChronoUnit.DAYS),
        Map.entry("hour", ChronoUnit.HOURS), Map.entry("hours", ChronoUnit.HOURS), Map.entry("시간", ChronoUnit.HOURS),
        Map.entry("minute", ChronoUnit.MINUTES), Map.entry("minutes", ChronoUnit.MINUTES),
        Map.entry("분", ChronoUnit.MINUTES),
        Map.entry("second", ChronoUnit.SECONDS), Map.entry("seconds", ChronoUnit.SECONDS),
        Map.entry("초", ChronoUnit.SECONDS));

    private static final Map<String, String> PATTERNS = Map.of(
        "korean", "yyyy년 M월 d일 (E)",
        "korean_time", "yyyy년 M월 d일 (E) HH:mm",
        "date", "yyyy-MM-dd",
        "datetime", "yyyy-MM-dd HH:mm");

    private record Term(long amount, ChronoUnit unit) {
    }

    private DateFormats() {
    }

    static Map<String, Object> format(Map<String, Object> in, ZonedDateTime now) {
        String format = choice(in, "format", "korean",
            Set.of("korean", "korean_time", "date", "datetime", "iso", "custom"));
        DateTimeFormatter f = switch (format) {
            case "iso" -> DateTimeFormatter.ISO_OFFSET_DATE_TIME;
            case "custom" -> custom(in);
            default -> DateTimeFormatter.ofPattern(PATTERNS.get(format), Locale.KOREAN);
        };
        ZoneId from = zone(in, "fromTimezone");
        ZoneId to = zone(in, "toTimezone");
        ConditionValues.When w = read(in, "input", "입력값", now, from);
        ZonedDateTime z = (w.dateOnly() ? w.day().atStartOfDay(from) : w.instant().atZone(from))
            .withZoneSameInstant(to)
            .truncatedTo(ChronoUnit.SECONDS);
        try {
            return Map.of("output", z.format(f));
        } catch (DateTimeException e) {
            throw new FormatterFailure("직접 정한 모양으로 표기하지 못했어요");
        }
    }

    static Map<String, Object> add(Map<String, Object> in, ZonedDateTime now) {
        List<Term> terms = terms(FormatterInputs.required(in, "expression", "더하거나 뺄 기간"));
        ConditionValues.When w = read(in, "input", "입력값", now, KST);
        if (w.dateOnly() && terms.stream().allMatch(t -> t.unit().isDateBased())) {
            LocalDate d = w.day();
            for (Term t : terms) {
                d = d.plus(t.amount(), t.unit());
            }
            return Map.of("output", d.toString());
        }
        ZonedDateTime z = w.instant().atZone(KST);
        for (Term t : terms) {
            z = z.plus(t.amount(), t.unit());
        }
        return Map.of("output", z.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }

    static Map<String, Object> compare(Map<String, Object> in, ZonedDateTime now) {
        ConditionValues.When a = read(in, "input", "기준 날짜", now, KST);
        ConditionValues.When b = read(in, "other", "비교할 날짜", now, KST);
        long days;
        long hours;
        long minutes;
        if (a.dateOnly() || b.dateOnly()) {
            days = ChronoUnit.DAYS.between(a.day(), b.day());
            hours = days * 24;
            minutes = days * 1440;
        } else {
            Duration d = Duration.between(a.instant(), b.instant());
            days = d.toDays();
            hours = d.toHours();
            minutes = d.toMinutes();
        }
        return Map.of("days", days, "hours", hours, "minutes", minutes,
            "isBefore", ConditionValues.compare(a, b) < 0);
    }

    private static ConditionValues.When read(
        Map<String, Object> in, String key, String fieldTitle, ZonedDateTime now, ZoneId zone) {
        String s = FormatterInputs.required(in, key, fieldTitle);
        return ConditionValues.date(s, now, zone).orElseThrow(() ->
            new FormatterFailure("'" + FormatterInputs.summary(s) + "'를 날짜로 읽지 못했어요"));
    }

    /** 비우면 KST. */
    private static ZoneId zone(Map<String, Object> in, String key) {
        String s = FormatterInputs.text(in, key).strip();
        if (s.isEmpty()) {
            return KST;
        }
        try {
            return ZoneId.of(s);
        } catch (DateTimeException e) {
            throw new FormatterFailure("알 수 없는 시간대예요: " + FormatterInputs.summary(s));
        }
    }

    private static DateTimeFormatter custom(Map<String, Object> in) {
        String p = FormatterInputs.required(in, "customPattern", "직접 정한 모양");
        if (p.length() > FormatterInputs.MAX_CUSTOM_PATTERN) {
            throw new FormatterFailure("'직접 정한 모양' 칸은 100자까지예요");
        }
        try {
            return DateTimeFormatter.ofPattern(p, Locale.KOREAN);
        } catch (IllegalArgumentException e) {
            throw new FormatterFailure("'직접 정한 모양'이 올바른 날짜 패턴이 아니에요");
        }
    }

    /** {@code +1 month -2 days}·{@code +3일 -2시간}. 부호 없는 항·모르는 단위·남는 글자는 실패. */
    private static List<Term> terms(String expr) {
        return FormatterInputs.regex(() -> {
            Matcher m = TERM.matcher(FormatterInputs.timed(expr));
            List<Term> out = new ArrayList<>();
            int pos = 0;
            while (pos < expr.length()) {
                m.region(pos, expr.length());
                if (!m.lookingAt()) {
                    throw new FormatterFailure("기간 식을 읽지 못했어요(예: +1 month -2 days)");
                }
                if (out.size() == MAX_TERMS) {
                    throw new FormatterFailure("기간 식은 " + MAX_TERMS + "항까지예요");
                }
                if (m.group(2).length() > MAX_AMOUNT_DIGITS) {
                    throw new FormatterFailure("기간 숫자가 너무 커요(6자리까지)");
                }
                ChronoUnit unit = UNITS.get(m.group(3).toLowerCase(Locale.ROOT));
                if (unit == null) {
                    throw new FormatterFailure("알 수 없는 단위예요: " + FormatterInputs.summary(m.group(3)));
                }
                long n = Long.parseLong(m.group(2));
                out.add(new Term(m.group(1).equals("-") ? -n : n, unit));
                pos = m.end();
            }
            return out;
        });
    }
}
