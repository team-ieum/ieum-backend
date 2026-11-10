package com.ieum.api.trigger.forms;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forms 구독 커서 {@code {afterSec, floorSec, okSec, seen{key: sec}}} — {@code GmailCursor}와 같은 모양·의미(spec: Forms 전용 클래스).
 *
 * <ul>
 *   <li>{@code afterSec} — 발화한 응답 중 가장 늦은 이벤트 초(T1 createTime·T2 lastSubmittedTime), 지금을 넘지 않는다.</li>
 *   <li>{@code floorSec} — 구독(또는 리셋) 시각. 그 이전 응답은 발화하지 않는다. 없으면 {@code afterSec}.</li>
 *   <li>{@code okSec} — 마지막으로 성공한 폴링 시각. 없으면 {@code afterSec}.</li>
 *   <li>{@code seen} — lookback 창 안에서 이미 발화한 중복 키(T1 responseId, T2 responseId@lastSubmittedTime).</li>
 * </ul>
 *
 * <p>lookback: 목록 정렬 보장이 없고(실호출 ②) 늦게 보이는 응답이 있을 수 있어 {@code afterSec - LOOKBACK_SEC}부터 다시 본다.
 */
final class FormsCursor {

    static final long LOOKBACK_SEC = 600;

    private long afterSec;
    private final long floorSec;
    private long okSec;
    private final Map<String, Long> seen = new LinkedHashMap<>();

    private FormsCursor(long afterSec, long floorSec, long okSec) {
        this.afterSec = afterSec;
        this.floorSec = floorSec;
        this.okSec = okSec;
    }

    /** JSON에서 돌아온 숫자는 Integer일 수 있다 — Number로 읽는다. 비어 있으면 fallback 초에서 새로 시작 */
    static FormsCursor from(Map<String, Object> raw, long fallbackSec) {
        long after = raw != null && raw.get("afterSec") instanceof Number n ? n.longValue() : fallbackSec;
        long floor = raw != null && raw.get("floorSec") instanceof Number n ? n.longValue() : after;
        long ok = raw != null && raw.get("okSec") instanceof Number n ? n.longValue() : after;
        FormsCursor cursor = new FormsCursor(after, floor, ok);
        if (raw != null && raw.get("seen") instanceof Map<?, ?> keys) {
            keys.forEach((key, sec) -> {
                if (sec instanceof Number n) {
                    cursor.seen.put(String.valueOf(key), n.longValue());
                }
            });
        }
        return cursor;
    }

    long okSec() {
        return okSec;
    }

    void markOk(long nowSec) {
        okSec = nowSec;
    }

    /** 이 초보다 이른 응답은 보지 않는다 — lookback 창의 시작, 단 구독 시각 이전으로는 내려가지 않는다 */
    long lowSec() {
        return Math.max(floorSec, afterSec - LOOKBACK_SEC);
    }

    boolean seen(String key) {
        return seen.containsKey(key);
    }

    /** 발화한 응답 1건 기록. afterSec은 지금을 넘기지 않고, seen에는 실제 초를 둔다 */
    void advance(String key, long sec) {
        afterSec = Math.max(afterSec, Math.min(sec, Instant.now().getEpochSecond()));
        seen.put(key, sec);
    }

    /** 발화하지 않고 건너뛴 응답 — seen에만 넣는다(afterSec은 그대로) */
    void skip(String key, long sec) {
        seen.put(key, sec);
    }

    Map<String, Object> toMap() {
        long low = lowSec();
        seen.values().removeIf(sec -> sec < low);
        return Map.of("afterSec", afterSec, "floorSec", floorSec, "okSec", okSec, "seen", new LinkedHashMap<>(seen));
    }
}
