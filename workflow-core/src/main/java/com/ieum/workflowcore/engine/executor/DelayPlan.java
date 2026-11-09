package com.ieum.workflowcore.engine.executor;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 기다리기 노드 하나의 재개 시각 계산 결과.
 *
 * @param scheduledFor  재개 예정 시각
 * @param decidedAt     계산한 시각(지금)
 * @param queuePosition 큐(delay.queue)만 — 앞에 아직 안 나간 실행 수. 그 밖은 null
 */
public record DelayPlan(Instant scheduledFor, Instant decidedAt, Integer queuePosition) {

    /** 예정 시각이 이미 지났거나 지금이면 기다리지 않고 통과한다. */
    public boolean immediate() {
        return !scheduledFor.isAfter(decidedAt);
    }

    /** 기다리는 동안의 출력 — {@code workflow_runs.waiting_delay_nodes}에 노드별로 저장된다. */
    public Map<String, Object> waitingOutput() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scheduledFor", format(scheduledFor));
        if (queuePosition != null) out.put("queuePosition", queuePosition);
        return out;
    }

    /** 통과한 뒤의 노드 출력. */
    public Map<String, Object> releasedOutput(Instant waitedUntil) {
        Map<String, Object> out = waitingOutput();
        out.put("waitedUntil", format(waitedUntil));
        return out;
    }

    /** 출력 시각 형식 — KST {@code ISO_OFFSET_DATE_TIME}, 초 단위 절사. 예 {@code 2026-11-09T10:40:00+09:00}. */
    public static String format(Instant t) {
        return t.truncatedTo(ChronoUnit.SECONDS).atZone(ConditionValues.KST)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
