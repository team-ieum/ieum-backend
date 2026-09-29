package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StubExecutionJobEnqueuerTest {

    @Test
    @DisplayName("enqueue는 항상 false(큐 없음 → 호출부 직접 실행)를 반환한다")
    void enqueueAlwaysReturnsFalse() {
        assertThat(new StubExecutionJobEnqueuer().enqueue(UUID.randomUUID())).isFalse();
    }
}
