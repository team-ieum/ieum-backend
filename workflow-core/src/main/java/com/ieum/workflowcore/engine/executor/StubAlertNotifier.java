package com.ieum.workflowcore.engine.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * AlertNotifier 임시 Stub 구현체.
 * api 모듈의 {@code DefaultAlertNotifier}가 등록되면 자동으로 대체된다 (@ConditionalOnMissingBean).
 *
 * <p>no-op이다 — 미구현 환경(workflow-core 단독 테스트, 모듈 빌드 등)에서 빈 주입 오류 없이
 * 기동하고 알림만 조용히 빠진다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(value = AlertNotifier.class, ignored = StubAlertNotifier.class)
public class StubAlertNotifier implements AlertNotifier {

    @Override
    public void notifyExecutionFailed(ExecutionFailureAlert alert) {
        log.debug("[StubAlertNotifier] AlertNotifier 미구현 — 알림 생략. executionId: {}",
            alert != null ? alert.executionId() : null);
    }
}
