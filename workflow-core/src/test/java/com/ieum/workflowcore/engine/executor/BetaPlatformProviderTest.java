package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BetaPlatformProviderTest {

    private final BetaPlatformProvider provider = mock(BetaPlatformProvider.class, CALLS_REAL_METHODS);

    @Test
    @DisplayName("releaseQuietly — 키가 null(예약 전 실패)이면 환불하지 않는다")
    void releaseQuietly_nullKey_noOp() {
        provider.releaseQuietly(null);

        verify(provider, never()).releaseDailyCall(any());
    }

    @Test
    @DisplayName("releaseQuietly — 환불이 실패해도 예외를 밖으로 던지지 않는다(원래 실패를 가리지 않게)")
    void releaseQuietly_releaseFails_swallowed() {
        doThrow(new IllegalStateException("redis down")).when(provider).releaseDailyCall("k");

        assertThatCode(() -> provider.releaseQuietly("k")).doesNotThrowAnyException();
        verify(provider).releaseDailyCall("k");
    }
}
