package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StubIdempotencyStoreTest {

    private final StubIdempotencyStore store = new StubIdempotencyStore();

    @Test
    @DisplayName("markInFlight는 항상 true(마커 없었음)를 반환한다")
    void markInFlightAlwaysReturnsTrue() {
        assertThat(store.markInFlight("any-key", Duration.ofMinutes(5))).isTrue();
        assertThat(store.markInFlight("any-key", Duration.ofMinutes(5))).isTrue();
    }

    @Test
    @DisplayName("clearInFlight는 아무 예외 없이 no-op으로 동작한다")
    void clearInFlightIsNoOp() {
        assertThatCode(() -> store.clearInFlight("any-key")).doesNotThrowAnyException();
    }
}
