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
}
