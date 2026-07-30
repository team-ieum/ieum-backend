package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stub이 no-op이라 workflow-core 단독 실행이 깨지지 않는다는 것을 고정한다.
 * 여기서 throw하면 api 없이 도는 모든 실행 경로가 실패한다.
 */
class StubAlertNotifierTest {

    private final StubAlertNotifier notifier = new StubAlertNotifier();

    @Test
    @DisplayName("Stub은 알림을 발신하지 않고 예외도 던지지 않는다")
    void notifyExecutionFailed_isNoOp() {
        AlertNotifier.ExecutionFailureAlert alert = new AlertNotifier.ExecutionFailureAlert(
                UUID.randomUUID(), UUID.randomUUID(), "워크플로우", UUID.randomUUID(),
                "node-a", "오류", true);

        assertThatCode(() -> notifier.notifyExecutionFailed(alert)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Stub은 null 페이로드에도 터지지 않는다")
    void notifyExecutionFailed_nullAlert_isNoOp() {
        assertThatCode(() -> notifier.notifyExecutionFailed(null)).doesNotThrowAnyException();
    }
}
