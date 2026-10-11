package com.ieum.api.trigger.gmail;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gmail 구독 커서 {@code {afterSec, floorSec, okSec, seen{id: sec}}}.
 *
 * <ul>
 *   <li>{@code afterSec} — 발화한 메일 중 가장 늦은 수신 초.</li>
 *   <li>{@code floorSec} — 구독(또는 리셋) 시각. 그 이전 메일은 절대 발화하지 않는다. 없으면 {@code afterSec}(갓 만든 커서).</li>
 *   <li>{@code okSec} — 마지막으로 성공한 폴링 시각. 없으면 {@code afterSec}. 오래 실패만 했는지 판단하는 기준이다
 *       ({@code afterSec}은 메일이 안 오면 멈춰 있으니 쓸 수 없다).</li>
 *   <li>{@code seen} — lookback 창 안에서 이미 발화한 id. 창 밖으로 밀린 항목은 버린다.</li>
 * </ul>
 *
 * <p>lookback: Gmail 검색 색인은 수 초~수 분 늦을 수 있어, 늦게 색인된 메일보다 뒤 메일이 먼저 발화하면 커서가 앞 메일을
 * 지나친다. 그래서 {@code afterSec - LOOKBACK_SEC}부터 다시 보고 {@code seen}으로 중복을 거른다. 창보다 늦게 색인되면 여전히 유실이다.
 */
final class GmailCursor {

    static final long LOOKBACK_SEC = 600;

    private long afterSec;
    private final long floorSec;
    private long okSec;
    private final Map<String, Long> seen = new LinkedHashMap<>();

    private GmailCursor(long afterSec, long floorSec, long okSec) {
        this.afterSec = afterSec;
        this.floorSec = floorSec;
        this.okSec = okSec;
    }

    /** JSON에서 돌아온 숫자는 Integer일 수 있다(epoch 초가 int 범위) — Number로 읽는다. 비어 있으면 fallback 초에서 새로 시작. */
    static GmailCursor from(Map<String, Object> raw, long fallbackSec) {
        long after = raw != null && raw.get("afterSec") instanceof Number n ? n.longValue() : fallbackSec;
        long floor = raw != null && raw.get("floorSec") instanceof Number n ? n.longValue() : after;
        long ok = raw != null && raw.get("okSec") instanceof Number n ? n.longValue() : after;
        GmailCursor cursor = new GmailCursor(after, floor, ok);
        if (raw != null && raw.get("seen") instanceof Map<?, ?> ids) {
            ids.forEach((id, sec) -> {
                if (sec instanceof Number n) {
                    cursor.seen.put(String.valueOf(id), n.longValue());
                }
            });
        }
        return cursor;
    }

    long afterSec() {
        return afterSec;
    }

    long okSec() {
        return okSec;
    }

    void markOk(long nowSec) {
        okSec = nowSec;
    }

    /** 이 초보다 이른 메일은 보지 않는다 — lookback 창의 시작, 단 구독 시각 이전으로는 내려가지 않는다. */
    long lowSec() {
        return Math.max(floorSec, afterSec - LOOKBACK_SEC);
    }

    boolean seen(String messageId) {
        return seen.containsKey(messageId);
    }

    /** 발화한 메일 1건 기록. lookback 창 안에서는 순서가 뒤바뀌어 들어와도 된다. */
    void advance(String messageId, long sec) {
        afterSec = Math.max(afterSec, sec);
        seen.put(messageId, sec);
    }

    Map<String, Object> toMap() {
        long low = lowSec();
        seen.values().removeIf(sec -> sec < low);
        return Map.of("afterSec", afterSec, "floorSec", floorSec, "okSec", okSec, "seen", new LinkedHashMap<>(seen));
    }
}
