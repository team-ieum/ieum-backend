package com.ieum.workflowcore.trigger;

import java.time.LocalDateTime;
import java.util.Map;

/** 구독 행의 폴링 시작 상태 — 커서와 다음 폴링 시각. 폴링하지 않는 트리거는 {@link #NONE}. */
public record PollStart(Map<String, Object> cursor, LocalDateTime nextPollAt) {

    public static final PollStart NONE = new PollStart(null, null);
}
