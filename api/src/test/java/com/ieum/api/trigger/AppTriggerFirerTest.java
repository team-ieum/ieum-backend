package com.ieum.api.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppTriggerFirerTest {

    private final WorkflowCrudService crud = mock(WorkflowCrudService.class);
    private final WorkflowExecutionService executions = mock(WorkflowExecutionService.class);
    private final WorkflowExecutionRunner runner = mock(WorkflowExecutionRunner.class);
    private final AppTriggerFirer firer = new AppTriggerFirer(crud, executions, runner);
    private final UUID workflowId = UUID.randomUUID();

    @Test
    @DisplayName("활성 + 버전 있음 → 실행 준비(같은 출력이 triggerData) 후 러너 투입")
    void firesActiveWorkflow() {
        Workflow workflow = mock(Workflow.class);
        WorkflowVersion version = mock(WorkflowVersion.class);
        WorkflowExecution execution = mock(WorkflowExecution.class);
        UUID executionId = UUID.randomUUID();
        Map<String, Object> output = Map.of("subject", "s");
        given(workflow.getTriggerType()).willReturn(TriggerType.GMAIL_NEW_EMAIL);
        given(crud.findActiveById(workflowId)).willReturn(workflow);
        given(crud.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(executions.prepareExecution(workflow, version, TriggerType.GMAIL_NEW_EMAIL, output)).willReturn(execution);
        given(execution.getId()).willReturn(executionId);

        assertThat(firer.fire(workflowId, TriggerType.GMAIL_NEW_EMAIL, output)).isTrue();
        verify(runner).run(version, executionId, output);
    }

    @Test
    @DisplayName("비활성(또는 삭제) → 실행을 만들지 않고 false")
    void inactiveDoesNothing() {
        given(crud.findActiveById(workflowId)).willReturn(null);

        assertThat(firer.fire(workflowId, TriggerType.GMAIL_NEW_EMAIL, Map.of())).isFalse();
        verifyNoInteractions(executions, runner);
    }

    @Test
    @DisplayName("버전 없음 → false")
    void noVersionDoesNothing() {
        Workflow workflow = mock(Workflow.class);
        given(workflow.getTriggerType()).willReturn(TriggerType.GMAIL_NEW_EMAIL);
        given(crud.findActiveById(workflowId)).willReturn(workflow);
        given(crud.findLatestVersion(workflowId)).willReturn(Optional.empty());

        assertThat(firer.fire(workflowId, TriggerType.GMAIL_NEW_EMAIL, Map.of())).isFalse();
        verify(executions, org.mockito.Mockito.never()).prepareExecution(any(), any(), any(), any());
    }

    @Test
    @DisplayName("폴링 도중 다른 트리거로 바뀜 → 실행을 만들지 않고 false")
    void triggerTypeChangedDoesNothing() {
        Workflow workflow = mock(Workflow.class);
        given(workflow.getTriggerType()).willReturn(TriggerType.MANUAL);
        given(crud.findActiveById(workflowId)).willReturn(workflow);

        assertThat(firer.fire(workflowId, TriggerType.GMAIL_NEW_EMAIL, Map.of())).isFalse();
        verifyNoInteractions(executions, runner);
    }
}
