package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.util.SensitiveDataMasker;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 사람 확인 게이트(APPROVAL) config 해석 — 렌더된 config에 기본값·한도를 적용한다.
 * 잘못된 설정은 {@link ApprovalFailure}, 렌더 값 상한 초과는 잘라 끝에 {@code "…"}를 붙인다(실패 아님).
 *
 * <p>{@link #view()}는 {@link #parse}의 입력과 같은 키·모양이라 {@code parse(event, view())}가 같은 설정을 돌려준다.
 */
public final class ApprovalGateConfig {

    public static final String REQUEST = "approval.request";
    public static final String COLLECT = "approval.collect";
    public static final Set<String> EVENTS = Set.of(REQUEST, COLLECT);

    public static final Duration MIN_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration MAX_TIMEOUT = Duration.ofDays(7);
    public static final int MAX_ITEMS = 20;
    public static final int MAX_MESSAGE = 2_000;
    public static final int MAX_ITEM_VALUE = 10_000;
    public static final int MAX_LABEL = 75;
    public static final int MAX_FIELD_LABEL = 100;
    public static final int MAX_NOTE = 200;
    public static final int MAX_SHORT = 1_000;
    public static final int MAX_LONG = 10_000;
    public static final int MAX_OPTIONS = 50;
    public static final int MAX_OPTION = 100;
    public static final String KEY_PATTERN = "^[A-Za-z_][A-Za-z0-9_]{0,63}$";

    private static final Pattern KEY = Pattern.compile(KEY_PATTERN);
    private static final Set<String> TYPES = Set.of("short", "long", "number", "date", "boolean", "choice");
    private static final String RANGE = "제한 시간은 5분~7일이에요";
    private static final int MAX_ECHO = 50;

    public record Item(String key, String value) {
    }

    public record InputField(String key, String label, String type, boolean required, String defaultValue,
                             List<String> options) {
    }

    private final String event;
    private final String message;
    private final long timeoutAmount;
    private final String timeoutUnit;
    private final Duration timeout;
    private final boolean continueOnTimeout;
    private final boolean continueOnReject;
    private final boolean reminder;
    private final UUID webhookId;
    private final boolean editable;
    private final String approveLabel;
    private final String rejectLabel;
    private final List<Item> items;
    private final List<InputField> fields;

    private ApprovalGateConfig(String event, Map<String, Object> c) {
        this.event = event;
        String fallback = REQUEST.equals(event) ? "승인이 필요해요" : "값을 입력해 주세요";
        this.message = clipped(text(c.get("message"), fallback), MAX_MESSAGE);
        this.timeoutAmount = amount(c.get("timeoutAmount"));
        this.timeoutUnit = c.get("timeoutUnit") == null ? "hours" : String.valueOf(c.get("timeoutUnit"));
        this.timeout = timeout(timeoutAmount, timeoutUnit);
        this.continueOnTimeout = choice(c.get("onTimeout"), "'제한 시간이 지나면' 값이 올바르지 않아요");
        this.continueOnReject = choice(c.get("onReject"), "'거절하면' 값이 올바르지 않아요");
        this.reminder = flag(c.get("reminder"));
        this.webhookId = uuid(c.get("webhookCredentialId"));
        this.editable = flag(c.get("editable"));
        this.approveLabel = clipped(text(c.get("approveLabel"), "승인"), MAX_LABEL);
        this.rejectLabel = clipped(text(c.get("rejectLabel"), "거절"), MAX_LABEL);
        this.items = REQUEST.equals(event) ? items(c.get("items")) : List.of();
        this.fields = COLLECT.equals(event) ? fields(c.get("fields")) : List.of();
    }

    /** {@code event}가 null이면 승인 요청으로 본다. */
    public static ApprovalGateConfig parse(String event, Map<String, Object> rendered) {
        String e = event == null ? REQUEST : event;
        if (!EVENTS.contains(e)) {
            throw new ApprovalFailure("지원하지 않는 사람 확인이에요: " + clip(e));
        }
        return new ApprovalGateConfig(e, rendered == null ? Map.of() : rendered);
    }

    public String event() {
        return event;
    }

    public String message() {
        return message;
    }

    public Duration timeout() {
        return timeout;
    }

    public boolean continueOnTimeout() {
        return continueOnTimeout;
    }

    public boolean continueOnReject() {
        return continueOnReject;
    }

    public boolean reminder() {
        return reminder;
    }

    public UUID webhookId() {
        return webhookId;
    }

    public boolean editable() {
        return editable;
    }

    public String approveLabel() {
        return approveLabel;
    }

    public String rejectLabel() {
        return rejectLabel;
    }

    public List<Item> items() {
        return items;
    }

    public List<InputField> fields() {
        return fields;
    }

    /** {@code view_json}·상세 API 모양 = {@link #parse} 입력 모양. */
    public Map<String, Object> view() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("event", event);
        v.put("message", message);
        v.put("items", items.stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", i.key());
            m.put("value", i.value());
            return m;
        }).toList());
        v.put("editable", editable);
        v.put("approveLabel", approveLabel);
        v.put("rejectLabel", rejectLabel);
        v.put("fields", fields.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", f.key());
            m.put("label", f.label());
            m.put("type", f.type());
            m.put("required", f.required());
            m.put("default", f.defaultValue());
            m.put("options", f.options().isEmpty() ? null : String.join(",", f.options()));
            return m;
        }).toList());
        v.put("onReject", continueOnReject ? "continue" : "stop");
        v.put("onTimeout", continueOnTimeout ? "continue" : "stop");
        v.put("timeoutAmount", timeoutAmount);
        v.put("timeoutUnit", timeoutUnit);
        v.put("reminder", reminder);
        v.put("webhookCredentialId", webhookId == null ? null : webhookId.toString());
        return v;
    }

    /** 승인 요청의 검토 내용 {@code {key: value}}. */
    public Map<String, Object> contentValues() {
        Map<String, Object> out = new LinkedHashMap<>();
        items.forEach(i -> out.put(i.key(), i.value()));
        return out;
    }

    /** 값 입력받기의 렌더된 기본값을 타입대로 변환한다. 비었거나 변환에 실패한 칸은 뺀다. */
    public Map<String, Object> defaultValues() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (InputField f : fields) {
            if (f.defaultValue() == null || f.defaultValue().isBlank()) continue;
            try {
                out.put(f.key(), convert(f, f.defaultValue()));
            } catch (ApprovalFailure ignored) {
                // 변환 실패 칸은 출력에서 빠진다
            }
        }
        return out;
    }

    /** 사용자가 제출한 값을 검사·변환한다. 빈 선택 칸은 키 없음. */
    public static Map<String, Object> coerce(List<InputField> fields, Map<String, Object> submitted) {
        Map<String, Object> in = submitted == null ? Map.of() : submitted;
        Set<String> declared = new HashSet<>();
        fields.forEach(f -> declared.add(f.key()));
        for (String key : in.keySet()) {
            if (!declared.contains(key)) throw new ApprovalFailure("알 수 없는 칸이에요: '" + clip(key) + "'");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (InputField f : fields) {
            Object value = in.get(f.key());
            if (value == null || String.valueOf(value).isBlank()) {
                if (f.required()) throw new ApprovalFailure("'" + f.label() + "' 칸을 채워 주세요");
                continue;
            }
            out.put(f.key(), convert(f, value));
        }
        return out;
    }

    private static Object convert(InputField f, Object value) {
        String label = "'" + f.label() + "'";
        return switch (f.type()) {
            case "number" -> {
                BigDecimal n = ConditionValues.number(value)
                    .orElseThrow(() -> new ApprovalFailure(label + "은 숫자여야 해요"));
                // 거대 지수(1e999999999)는 node_runs로 옮길 때 Double "Infinity"가 된다
                if (!NumberFormats.withinDigits(n)) {
                    throw new ApprovalFailure(label + "은 정수·소수 각 1000자리까지예요");
                }
                // node_runs는 기본 ObjectMapper(Double)로 다시 읽어 1.8e308을 넘으면 "Infinity"가 된다
                if (Double.isInfinite(n.doubleValue())) {
                    throw new ApprovalFailure(label + "은 너무 큰 숫자예요");
                }
                yield n;
            }
            case "boolean" -> {
                Boolean b = value instanceof Boolean v ? v : ConditionValues.bool(value);
                if (b == null) throw new ApprovalFailure(label + "은 예/아니오여야 해요");
                yield b;
            }
            case "date" -> {
                String error = label + "을 날짜로 읽지 못했어요: '" + clip(value) + "'";
                try {
                    yield ConditionValues.date(value, ZonedDateTime.now(ConditionValues.KST))
                        .map(w -> DelayPlan.format(w.instant()))
                        .orElseThrow(() -> new ApprovalFailure(error));
                } catch (DateTimeException e) {
                    // 읽히지만 KST로 옮기면 범위 밖인 극단값(+999999999년 등)
                    throw new ApprovalFailure(error);
                }
            }
            case "choice" -> {
                String s = String.valueOf(value).strip();
                if (!f.options().contains(s)) throw new ApprovalFailure(label + "은 보기 중에서 골라 주세요");
                yield s;
            }
            default -> {
                String s = String.valueOf(value);
                int max = "long".equals(f.type()) ? MAX_LONG : MAX_SHORT;
                if (s.length() > max) throw new ApprovalFailure(label + "은 " + max + "자 이하여야 해요");
                yield s;
            }
        };
    }

    private static long amount(Object raw) {
        if (raw == null) return 24;
        BigDecimal n = ConditionValues.number(raw)
            .filter(v -> v.stripTrailingZeros().scale() <= 0 && v.compareTo(BigDecimal.ONE) >= 0)
            .orElseThrow(() -> new ApprovalFailure("'제한 시간'은 1 이상의 정수여야 해요"));
        // 7일 = 10,080분 — 그보다 큰 양은 단위와 상관없이 범위 밖이다(longValueExact 오버플로 차단)
        if (n.compareTo(BigDecimal.valueOf(MAX_TIMEOUT.toMinutes())) > 0) throw new ApprovalFailure(RANGE);
        return n.longValueExact();
    }

    private static Duration timeout(long amount, String unit) {
        Duration step = switch (unit) {
            case "minutes" -> Duration.ofMinutes(1);
            case "hours" -> Duration.ofHours(1);
            case "days" -> Duration.ofDays(1);
            default -> throw new ApprovalFailure("'제한 시간 단위'를 골라 주세요: '" + clip(unit) + "'");
        };
        Duration d = step.multipliedBy(amount);
        if (d.compareTo(MIN_TIMEOUT) < 0 || d.compareTo(MAX_TIMEOUT) > 0) throw new ApprovalFailure(RANGE);
        return d;
    }

    /** stop(기본) = false, continue = true. */
    private static boolean choice(Object raw, String error) {
        if (raw == null) return false;
        return switch (String.valueOf(raw)) {
            case "stop" -> false;
            case "continue" -> true;
            default -> throw new ApprovalFailure(error);
        };
    }

    private static boolean flag(Object raw) {
        if (raw == null) return false;
        Boolean b = raw instanceof Boolean v ? v : ConditionValues.bool(raw);
        return Boolean.TRUE.equals(b);
    }

    private static UUID uuid(Object raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(String.valueOf(raw).strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<Item> items(Object raw) {
        List<Map<?, ?>> list = list(raw, "검토 내용");
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<?, ?> m : list) {
            String key = key(m.get("key"), seen);
            Object value = m.get("value");
            out.add(new Item(key, clipped(value == null ? "" : String.valueOf(value), MAX_ITEM_VALUE)));
        }
        return List.copyOf(out);
    }

    private static List<InputField> fields(Object raw) {
        List<Map<?, ?>> list = list(raw, "입력받을 칸");
        if (list.isEmpty()) throw new ApprovalFailure("입력받을 칸을 하나 이상 추가해 주세요");
        List<InputField> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<?, ?> m : list) {
            String key = key(m.get("key"), seen);
            // view·제출 오류 문구·노드 실패 문구에 통째로 실리므로 자른다
            String label = fitted(text(m.get("label"), key), MAX_FIELD_LABEL);
            String type = m.get("type") == null ? "short" : String.valueOf(m.get("type"));
            if (!TYPES.contains(type)) throw new ApprovalFailure("'" + label + "' 칸의 형식이 올바르지 않아요");
            List<String> options = options(m.get("options"), label);
            if ("choice".equals(type) && options.isEmpty()) {
                throw new ApprovalFailure("'" + label + "' 칸의 보기를 적어 주세요");
            }
            Object def = m.get("default");
            // 칸 타입 상한 안에 들게("…" 포함) 자른다 — 넘치면 convert가 실패해 기본값이 통째로 빠진다
            int max = "short".equals(type) ? MAX_SHORT : MAX_LONG;
            out.add(new InputField(key, label, type, flag(m.get("required")),
                def == null ? null : fitted(String.valueOf(def), max), options));
        }
        return List.copyOf(out);
    }

    private static List<Map<?, ?>> list(Object raw, String name) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw new ApprovalFailure("'" + name + "' 값이 올바르지 않아요");
        if (list.size() > MAX_ITEMS) throw new ApprovalFailure("'" + name + "'은 20개까지예요");
        List<Map<?, ?>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) throw new ApprovalFailure("'" + name + "' 값이 올바르지 않아요");
            out.add(m);
        }
        return out;
    }

    private static String key(Object raw, Set<String> seen) {
        String key = raw == null ? "" : String.valueOf(raw);
        if (!KEY.matcher(key).matches()) {
            throw new ApprovalFailure("이름은 영문·숫자·밑줄로 써 주세요: '" + clip(key) + "'");
        }
        // 마스킹 대상 이름이면 값이 하류에서 조용히 ***가 된다
        if (SensitiveDataMasker.isSensitiveKey(key)) {
            throw new ApprovalFailure("'" + key + "'은 민감정보 이름이라 쓸 수 없어요. 다른 이름을 써 주세요");
        }
        if (!seen.add(key)) throw new ApprovalFailure("이름이 겹쳐요: '" + key + "'");
        return key;
    }

    private static List<String> options(Object raw, String label) {
        if (raw == null) return List.of();
        List<String> out = Arrays.stream(String.valueOf(raw).split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .toList();
        if (out.size() > MAX_OPTIONS) throw new ApprovalFailure("'" + label + "' 칸의 보기는 50개까지예요");
        if (out.stream().anyMatch(s -> s.length() > MAX_OPTION)) {
            throw new ApprovalFailure("'" + label + "' 칸의 보기는 각 100자까지예요");
        }
        return out;
    }

    private static String text(Object raw, String fallback) {
        String s = raw == null ? "" : String.valueOf(raw);
        return s.isBlank() ? fallback : s;
    }

    /** 렌더 값 상한 — 넘으면 잘라 {@code "…"}를 붙인다. 같은 값을 다시 거쳐도 그대로다(view 왕복). */
    private static String clipped(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** {@link #clipped}와 같되 {@code "…"}까지 max자 안에 든다. 같은 값을 다시 거쳐도 그대로다. */
    private static String fitted(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** 실패 문구에 싣는 원문은 50자까지(DelayNodeExecutor.clip과 같다). */
    private static String clip(Object raw) {
        String s = String.valueOf(raw);
        return s.length() <= MAX_ECHO ? s : s.substring(0, MAX_ECHO) + "…";
    }
}
