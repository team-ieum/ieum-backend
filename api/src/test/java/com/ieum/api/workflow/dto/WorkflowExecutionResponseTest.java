package com.ieum.api.workflow.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class WorkflowExecutionResponseTest {

    private WorkflowExecution execution(ExecutionStatus status) {
        Workflow workflow = Workflow.builder().userId(UUID.randomUUID()).name("승인").isActive(true).build();
        ReflectionTestUtils.setField(workflow, "id", UUID.randomUUID());
        WorkflowExecution e = WorkflowExecution.builder()
            .workflow(workflow).workflowVersion(mock(WorkflowVersion.class)).status(status)
            .triggerType(TriggerType.MANUAL).startedAt(LocalDateTime.now()).build();
        ReflectionTestUtils.setField(e, "waitingApprovalNodeIds", "[\"g1\",\"g2\"]");
        return e;
    }

    @Test
    @DisplayName("승인 대기가 아닌 실행은 pendingApprovalNodeIds가 비고, 대기 실행은 waitingApprovalNodeIds를 싣는다")
    void 끝난_실행은_pending이_비어_있다() {
        WorkflowExecutionResponse waiting = WorkflowExecutionResponse.from(execution(ExecutionStatus.WAITING_APPROVAL));
        assertThat(waiting.getPendingApprovalNodeIds()).containsExactly("g1", "g2");

        for (ExecutionStatus status : new ExecutionStatus[] {ExecutionStatus.SUCCESS, ExecutionStatus.FAILED}) {
            WorkflowExecutionResponse done = WorkflowExecutionResponse.from(execution(status));
            assertThat(done.getPendingApprovalNodeIds()).isEmpty();
            assertThat(done.getWaitingApprovalNodeIds()).containsExactly("g1", "g2");
        }
    }
}
