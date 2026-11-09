package com.ieum.workflowcore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class WorkflowExecutionTest {

    @Test
    @DisplayName("대기 게이트 목록은 JSON 배열 컬럼을 읽는다 — 비었거나 깨졌으면 빈 목록(게이트 우회가 아니라 재대기 쪽으로 실패)")
    void waitingApprovalNodeIdList_readsJsonArray() {
        WorkflowExecution execution = WorkflowExecution.builder().build();
        assertThat(execution.waitingApprovalNodeIdList()).isEmpty();

        ReflectionTestUtils.setField(execution, "waitingApprovalNodeIds", "[\"g1\",\"g2\"]");
        assertThat(execution.waitingApprovalNodeIdList()).containsExactly("g1", "g2");

        ReflectionTestUtils.setField(execution, "waitingApprovalNodeIds", "not-json");
        assertThat(execution.waitingApprovalNodeIdList()).isEmpty();

        // JSON 리터럴 null은 readValue가 null을 돌려준다 — 그대로 새면 "빈 목록" 계약이 깨진다.
        ReflectionTestUtils.setField(execution, "waitingApprovalNodeIds", "null");
        assertThat(execution.waitingApprovalNodeIdList()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("기다리는 DELAY 노드 맵은 JSON 객체 컬럼을 읽는다 — 비었거나 깨졌으면 빈 맵(대기 우회가 아니라 재대기 쪽으로 실패)")
    void waitingDelayNodeMap_읽기() {
        WorkflowExecution execution = WorkflowExecution.builder().build();
        assertThat(execution.waitingDelayNodeMap()).isEmpty();

        ReflectionTestUtils.setField(execution, "waitingDelayNodes",
            "{\"d1\":{\"scheduledFor\":\"2026-11-09T10:40:00+09:00\",\"queuePosition\":2}}");
        assertThat(execution.waitingDelayNodeMap()).isEqualTo(java.util.Map.of(
            "d1", java.util.Map.of("scheduledFor", "2026-11-09T10:40:00+09:00", "queuePosition", 2)));

        ReflectionTestUtils.setField(execution, "waitingDelayNodes", "{깨짐");
        assertThat(execution.waitingDelayNodeMap()).isEmpty();
    }
}
