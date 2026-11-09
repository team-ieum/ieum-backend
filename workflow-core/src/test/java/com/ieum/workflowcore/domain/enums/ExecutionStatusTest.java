package com.ieum.workflowcore.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExecutionStatusTest {

    @Test
    @DisplayName("멈춘 상태(종료·대기)는 SUCCESS·FAILED·WAITING_APPROVAL·WAITING_DELAY 넷이다")
    void 멈춘_상태는_넷() {
        var stopped = EnumSet.allOf(ExecutionStatus.class).stream()
            .filter(ExecutionStatus::isTerminalOrWaiting)
            .collect(Collectors.toSet());

        assertThat(stopped).containsExactlyInAnyOrder(ExecutionStatus.SUCCESS, ExecutionStatus.FAILED,
            ExecutionStatus.WAITING_APPROVAL, ExecutionStatus.WAITING_DELAY);
    }
}
