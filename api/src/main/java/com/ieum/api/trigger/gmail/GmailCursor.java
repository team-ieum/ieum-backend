package com.ieum.api.trigger.gmail;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gmail 구독 커서 {@code {afterSec, seenIds[]}}. {@code seenIds}는 {@code afterSec} 그 초에 이미 발화한 id만 담는다 —
 * 같은 초에 온 메일이 다음 폴링에서 다시 잡혀도 중복 발화하지 않게.
 */
final class GmailCursor {

    private long afterSec;
    private final Set<String> seenIds = new LinkedHashSet<>();

    private GmailCursor(long afterSec) {
        this.afterSec = afterSec;
    }

    /** JSON에서 돌아온 값은 Integer일 수 있다(epoch 초가 int 범위) — Number로 읽는다. 비어 있으면 fallback 초. */
    static GmailCursor from(Map<String, Object> raw, long fallbackSec) {
        GmailCursor cursor = new GmailCursor(
            raw != null && raw.get("afterSec") instanceof Number n ? n.longValue() : fallbackSec);
        if (raw != null && raw.get("seenIds") instanceof List<?> ids) {
            ids.forEach(id -> cursor.seenIds.add(String.valueOf(id)));
        }
        return cursor;
    }

    long afterSec() {
        return afterSec;
    }

    boolean seen(String messageId) {
        return seenIds.contains(messageId);
    }

    /** 발화한 메일 1건만큼 전진. 오래된 순으로 부른다고 가정한다. */
    void advance(String messageId, long sec) {
        if (sec > afterSec) {
            afterSec = sec;
            seenIds.clear();
        }
        seenIds.add(messageId);
    }

    Map<String, Object> toMap() {
        return Map.of("afterSec", afterSec, "seenIds", new ArrayList<>(seenIds));
    }
}
