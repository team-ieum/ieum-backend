package com.ieum.workflowcore.engine.executor;

import static com.ieum.workflowcore.engine.executor.FormatterInputs.choice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 숫자 포맷터 4개. 결과는 전부 문자열({@link #plain}) — BigDecimal을 그대로 내면 하류가 {@code "1E+3"}을 받는다. */
final class NumberFormats {

    /** 정수부·소수부 자릿수 상한 — {@code "1e999999999"}가 연산·{@code toPlainString}에서 메모리를 터뜨리지 않게. */
    private static final int MAX_DIGITS = 1000;

    /** TextFormats 전화 추출의 한국 앞자리와 같은 규칙(02·010~019·031~065·070·080·050x) + 15xx~19xx 대표번호. */
    private static final Pattern PHONE = Pattern.compile(
        "0(" + TextFormats.AREA + ")(\\d{3,4})(\\d{4})|(1[5-9]\\d{2})(\\d{4})");

    private static final Map<String, String> SYMBOLS =
        Map.of("KRW", "₩", "USD", "$", "JPY", "¥", "EUR", "€", "CNY", "¥", "GBP", "£");

    private NumberFormats() {
    }

    static Map<String, Object> math(Map<String, Object> in, ZonedDateTime now) {
        String op = choice(in, "operation", null, Set.of("add", "subtract", "multiply", "divide", "negate"));
        BigDecimal a = number(in, "input", null);
        if (op.equals("negate")) {
            return out(plain(a.negate()));
        }
        BigDecimal b = number(in, "value", "계산할 값");
        if (op.equals("divide") && b.signum() == 0) {
            throw new FormatterFailure("0으로 나눌 수 없어요");
        }
        BigDecimal r = arith(() -> switch (op) {
            case "add" -> a.add(b);
            case "subtract" -> a.subtract(b);
            case "multiply" -> a.multiply(b);
            default -> a.divide(b, 10, RoundingMode.HALF_UP);
        });
        return out(plain(bounded(r)));
    }

    static Map<String, Object> format(Map<String, Object> in, ZonedDateTime now) {
        String th = switch (choice(in, "thousands", "comma", Set.of("comma", "period", "space", "none"))) {
            case "comma" -> ",";
            case "period" -> ".";
            case "space" -> " ";
            default -> "";
        };
        String dec = choice(in, "decimal", "period", Set.of("period", "comma")).equals("comma") ? "," : ".";
        if (th.equals(dec)) {
            throw new FormatterFailure("천 단위 구분과 소수점 기호가 같아요");
        }
        BigDecimal d = number(in, "input", null);
        return out(grouped(arith(() -> d.setScale(Math.max(0, d.scale()))), th, dec));
    }

    static Map<String, Object> currency(Map<String, Object> in, ZonedDateTime now) {
        String cur = choice(in, "currency", "KRW", SYMBOLS.keySet());
        String style = choice(in, "style", "korean", Set.of("korean", "symbol"));
        int scale = cur.equals("KRW") || cur.equals("JPY") ? 0 : 2;
        BigDecimal n = number(in, "input", null);
        BigDecimal d = arith(() -> n.setScale(scale, RoundingMode.HALF_UP));
        String body = grouped(d.abs(), ",", ".");
        String sign = d.signum() < 0 ? "-" : "";
        return out(cur.equals("KRW") && style.equals("korean") ? sign + body + "원" : sign + SYMBOLS.get(cur) + body);
    }

    static Map<String, Object> phone(Map<String, Object> in, ZonedDateTime now) {
        String format = choice(in, "format", "dash", Set.of("dash", "international", "digits"));
        String raw = FormatterInputs.text(in, "input").strip();
        String digits = raw.replaceAll("\\D", "");
        if (raw.startsWith("+82")) {
            digits = digits.substring(2);
            digits = digits.startsWith("0") ? digits : "0" + digits;
        }
        Matcher m = PHONE.matcher(digits);
        if (!m.matches()) {
            throw new FormatterFailure("한국 전화번호로 읽지 못했어요");
        }
        String[] parts = m.group(1) != null
            ? new String[] {m.group(1), m.group(2), m.group(3)}
            : new String[] {m.group(4), m.group(5)};
        return out(switch (format) {
            case "digits" -> digits;
            case "international" -> "+82 " + String.join("-", parts);
            default -> (m.group(1) != null ? "0" : "") + String.join("-", parts);
        });
    }

    /** 뒤 0 제거한 일반 표기. {@code -0} → {@code "0"}. */
    static String plain(BigDecimal d) {
        return d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
    }

    /** 10만 자 상한을 지나 {@code ConditionValues.number}로 읽고 자릿수 상한 검사. fieldTitle null = 입력값. */
    private static BigDecimal number(Map<String, Object> in, String key, String fieldTitle) {
        String text = FormatterInputs.text(in, key);
        if (fieldTitle != null && text.isEmpty()) {
            throw new FormatterFailure("'" + fieldTitle + "' 칸이 비어 있어요");
        }
        BigDecimal d = ConditionValues.number(text).orElseThrow(() -> new FormatterFailure(
            (fieldTitle == null ? "" : "'" + fieldTitle + "' 칸을 ") + "숫자로 읽지 못했어요"));
        return bounded(d);
    }

    /**
     * 원본 표기 그대로(0 포함) 검사 — {@code 0e-999999999}도 scale이 커서 {@code toPlainString}·연산이 터진다.
     * int 뺄셈은 {@code 1e2147483647}에서 넘치므로 long으로.
     */
    private static BigDecimal bounded(BigDecimal d) {
        if (!withinDigits(d)) {
            throw new FormatterFailure("숫자가 너무 커요(정수·소수 각 1000자리까지)");
        }
        return d;
    }

    /** 정수·소수 각 {@value #MAX_DIGITS}자리 이내인지 — 승인 게이트 숫자 칸도 같은 기준을 쓴다. */
    static boolean withinDigits(BigDecimal d) {
        return Math.abs((long) d.scale()) <= MAX_DIGITS && (long) d.precision() - d.scale() <= MAX_DIGITS;
    }

    /** 상한 검사를 지나고도 남는 산술 예외를 노드 실패로. */
    private static <T> T arith(Supplier<T> work) {
        try {
            return work.get();
        } catch (ArithmeticException e) {
            throw new FormatterFailure("계산하지 못했어요: " + e.getMessage());
        }
    }

    /** 정수부를 3자리마다 th로 묶고 소수점을 dec로. d의 scale은 0 이상이어야 한다. */
    private static String grouped(BigDecimal d, String th, String dec) {
        String s = d.abs().toPlainString();
        int dot = s.indexOf('.');
        String ip = dot < 0 ? s : s.substring(0, dot);
        StringBuilder b = new StringBuilder(d.signum() < 0 ? "-" : "");
        for (int i = 0; i < ip.length(); i++) {
            if (i > 0 && (ip.length() - i) % 3 == 0) {
                b.append(th);
            }
            b.append(ip.charAt(i));
        }
        return dot < 0 ? b.toString() : b.append(dec).append(s, dot + 1, s.length()).toString();
    }

    private static Map<String, Object> out(String s) {
        return Map.of("output", s);
    }
}
