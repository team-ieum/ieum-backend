package com.ieum.workflowcore.engine.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * AiUsageRecorder 임시 Stub 구현체.
 * api 모듈의 {@code DefaultAiUsageRecorder}가 등록되면 자동으로 대체된다 (@ConditionalOnMissingBean).
 * no-op이다 — workflow-core 단독 테스트에서 빈 주입 오류 없이 기동하고 기록만 빠진다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(value = AiUsageRecorder.class, ignored = StubAiUsageRecorder.class)
public class StubAiUsageRecorder implements AiUsageRecorder {

    @Override
    public void record(AiUsageCall call) {
        log.debug("[StubAiUsageRecorder] AiUsageRecorder 미구현 — 사용량 기록 생략");
    }
}
