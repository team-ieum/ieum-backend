package com.ieum.workflowcore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.common.exception.CustomException;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowExecutionLog;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.event.ExecutionEventSnapshot;
import com.ieum.workflowcore.engine.event.ExecutionEventType;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionServiceTest {

    @Mock private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock private WorkflowExecutionLogRepository workflowExecutionLogRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private WorkflowExecutionService service;

    @Test
    @DisplayName("종료된 실행 스냅샷은 노드 이벤트 + 종료 이벤트를 포함하고 terminal=true")
    void 종료된_실행_스냅샷() {
        UUID workflowId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        WorkflowExecution execution = mockExecution(workflowId, ExecutionStatus.SUCCESS);
        given(workflowExecutionRepository.findById(executionId)).willReturn(Optional.of(execution));

        WorkflowExecutionLog ok = mockLog(ExecutionLogStatus.SUCCESS, "node-1", NodeType.TRIGGER, 10L, null);
        WorkflowExecutionLog failed = mockLog(ExecutionLogStatus.FAILED, "node-2", NodeType.AI, 20L, "boom");
        given(workflowExecutionLogRepository.findByExecutionIdOrderByCreatedAtAsc(executionId))
            .willReturn(List.of(ok, failed));

        ExecutionEventSnapshot snapshot = service.loadEventSnapshot(workflowId, executionId);

        assertThat(snapshot.terminal()).isTrue();
        assertThat(snapshot.events()).hasSize(3);
        assertThat(snapshot.events().get(0).type()).isEqualTo(ExecutionEventType.NODE_COMPLETED);
        assertThat(snapshot.events().get(1).type()).isEqualTo(ExecutionEventType.NODE_FAILED);
        assertThat(snapshot.events().get(1).errorMessage()).isEqualTo("boom");
        assertThat(snapshot.events().get(2).type()).isEqualTo(ExecutionEventType.EXECUTION_COMPLETED);
    }

    @Test
    @DisplayName("진행 중 실행 스냅샷은 종료 이벤트 없이 terminal=false")
    void 진행중_실행_스냅샷() {
        UUID workflowId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        WorkflowExecution execution = mockExecution(workflowId, ExecutionStatus.RUNNING);
        given(workflowExecutionRepository.findById(executionId)).willReturn(Optional.of(execution));
        given(workflowExecutionLogRepository.findByExecutionIdOrderByCreatedAtAsc(executionId))
            .willReturn(List.of());

        ExecutionEventSnapshot snapshot = service.loadEventSnapshot(workflowId, executionId);

        assertThat(snapshot.terminal()).isFalse();
        assertThat(snapshot.events()).isEmpty();
    }

    @Test
    @DisplayName("executionId가 다른 워크플로우 소속이면 FORBIDDEN")
    void 워크플로우_불일치() {
        UUID workflowId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        WorkflowExecution execution = mockExecution(UUID.randomUUID(), ExecutionStatus.RUNNING);
        given(workflowExecutionRepository.findById(executionId)).willReturn(Optional.of(execution));

        assertThatThrownBy(() -> service.loadEventSnapshot(workflowId, executionId))
            .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("prepareExecution은 32자 무하이픈 hex traceId를 생성한다")
    void prepareExecution_generatesTraceId() {
        Workflow workflow = mock(Workflow.class);
        given(workflow.isActive()).willReturn(true);
        WorkflowVersion version = mock(WorkflowVersion.class);

        service.prepareExecution(workflow, version, TriggerType.MANUAL, Collections.emptyMap());

        ArgumentCaptor<WorkflowExecution> captor = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(workflowExecutionRepository).save(captor.capture());
        assertThat(captor.getValue().getTraceId()).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("prepareExecution은 triggerData를 JSON으로 저장한다")
    void prepareExecution_persistsTriggerDataAsJson() {
        Workflow workflow = mock(Workflow.class);
        given(workflow.isActive()).willReturn(true);
        WorkflowVersion version = mock(WorkflowVersion.class);

        service.prepareExecution(workflow, version, TriggerType.MANUAL,
            Map.of("city", "Seoul"));

        ArgumentCaptor<WorkflowExecution> captor = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(workflowExecutionRepository).save(captor.capture());
        assertThat(captor.getValue().getTriggerData()).contains("\"city\"", "\"Seoul\"");
    }

    @Test
    @DisplayName("triggerData의 민감 키(apiKey, token 등)는 ***로 마스킹되어 저장된다")
    void prepareExecution_masksSensitiveTriggerData() {
        Workflow workflow = mock(Workflow.class);
        given(workflow.isActive()).willReturn(true);
        WorkflowVersion version = mock(WorkflowVersion.class);

        service.prepareExecution(workflow, version, TriggerType.WEBHOOK,
            Map.of("apiKey", "sk-real-secret", "token", "raw-token", "city", "Seoul"));

        ArgumentCaptor<WorkflowExecution> captor = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(workflowExecutionRepository).save(captor.capture());
        String stored = captor.getValue().getTriggerData();
        assertThat(stored).doesNotContain("sk-real-secret", "raw-token");
        assertThat(stored).contains("\"apiKey\":\"***\"", "\"token\":\"***\"", "\"city\":\"Seoul\"");
    }

    @Test
    @DisplayName("triggerData가 null이어도 실행 준비가 깨지지 않는다")
    void prepareExecution_nullTriggerData_doesNotBreak() {
        Workflow workflow = mock(Workflow.class);
        given(workflow.isActive()).willReturn(true);
        WorkflowVersion version = mock(WorkflowVersion.class);

        service.prepareExecution(workflow, version, TriggerType.MANUAL, null);

        ArgumentCaptor<WorkflowExecution> captor = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(workflowExecutionRepository).save(captor.capture());
        assertThat(captor.getValue().getTriggerData()).isNull();
    }

    private WorkflowExecution mockExecution(UUID workflowId, ExecutionStatus status) {
        Workflow workflow = mock(Workflow.class);
        given(workflow.getId()).willReturn(workflowId);
        WorkflowExecution execution = mock(WorkflowExecution.class);
        given(execution.getWorkflow()).willReturn(workflow);
        // 워크플로우 불일치 케이스에서는 상태 조회 전에 throw되므로 lenient로 둔다.
        lenient().when(execution.getStatus()).thenReturn(status);
        return execution;
    }

    private WorkflowExecutionLog mockLog(ExecutionLogStatus status, String nodeId,
            NodeType nodeType, Long durationMs, String errorMessage) {
        WorkflowExecutionLog log = mock(WorkflowExecutionLog.class);
        given(log.getStatus()).willReturn(status);
        given(log.getNodeId()).willReturn(nodeId);
        given(log.getNodeType()).willReturn(nodeType);
        given(log.getDurationMs()).willReturn(durationMs);
        if (status == ExecutionLogStatus.FAILED) {
            given(log.getErrorMessage()).willReturn(errorMessage);
        }
        return log;
    }
}
