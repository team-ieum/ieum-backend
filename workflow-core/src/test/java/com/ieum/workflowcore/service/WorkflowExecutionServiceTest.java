package com.ieum.workflowcore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.common.exception.CustomException;
import com.ieum.common.util.AesEncryptor;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionServiceTest {

    // AesEncryptorTest와 동일한 32바이트 테스트 키 — 실제 암/복호화를 태워 round-trip을 검증한다.
    private static final String TEST_AES_KEY = "ieum-test-secret-key-32bytes-ok!";

    @Mock private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock private WorkflowExecutionLogRepository workflowExecutionLogRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Spy private AesEncryptor aesEncryptor = newAesEncryptor();
    @InjectMocks private WorkflowExecutionService service;

    private static AesEncryptor newAesEncryptor() {
        AesEncryptor encryptor = new AesEncryptor();
        ReflectionTestUtils.setField(encryptor, "secretKey", TEST_AES_KEY);
        encryptor.validateKey();
        return encryptor;
    }

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
    @DisplayName("prepareExecution은 triggerData를 평문이 아닌 암호문으로 저장한다")
    void prepareExecution_persistsTriggerDataEncrypted() {
        Workflow workflow = mock(Workflow.class);
        given(workflow.isActive()).willReturn(true);
        WorkflowVersion version = mock(WorkflowVersion.class);

        service.prepareExecution(workflow, version, TriggerType.MANUAL,
            Map.of("city", "Seoul"));

        ArgumentCaptor<WorkflowExecution> captor = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(workflowExecutionRepository).save(captor.capture());
        String stored = captor.getValue().getTriggerData();
        assertThat(stored).isNotNull();
        assertThat(stored).doesNotContain("city", "Seoul");
    }

    @Test
    @DisplayName("저장된 triggerData를 복호하면 민감 키를 포함한 원본 그대로 복원된다")
    void prepareExecution_encryptedTriggerData_decryptsToOriginal() throws Exception {
        Workflow workflow = mock(Workflow.class);
        given(workflow.isActive()).willReturn(true);
        WorkflowVersion version = mock(WorkflowVersion.class);
        Map<String, Object> original = Map.of(
            "apiKey", "sk-real-secret", "token", "raw-token", "city", "Seoul");

        service.prepareExecution(workflow, version, TriggerType.WEBHOOK, original);

        ArgumentCaptor<WorkflowExecution> captor = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(workflowExecutionRepository).save(captor.capture());
        String stored = captor.getValue().getTriggerData();

        String decryptedJson = aesEncryptor.decrypt(stored);
        Map<?, ?> decrypted = objectMapper.readValue(decryptedJson, Map.class);
        assertThat(decrypted).isEqualTo(original);
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

    @Test
    @DisplayName("decryptTriggerData는 prepareExecution이 암호화한 입력을 원본 Map으로 되돌린다")
    void decryptTriggerData_roundTripsPreparedInput() {
        Workflow workflow = mock(Workflow.class);
        given(workflow.isActive()).willReturn(true);
        WorkflowVersion version = mock(WorkflowVersion.class);
        Map<String, Object> original = Map.of("apiKey", "sk-real-secret", "city", "Seoul");

        service.prepareExecution(workflow, version, TriggerType.WEBHOOK, original);
        ArgumentCaptor<WorkflowExecution> captor = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(workflowExecutionRepository).save(captor.capture());

        WorkflowExecution stored = mock(WorkflowExecution.class);
        given(stored.getTriggerData()).willReturn(captor.getValue().getTriggerData());

        assertThat(service.decryptTriggerData(stored)).isEqualTo(original);
    }

    @Test
    @DisplayName("triggerData가 null이면 빈 Map을 반환한다 — 입력 없이 실행하던 기존 동작과 같다")
    void decryptTriggerData_nullTriggerData_returnsEmptyMap() {
        WorkflowExecution execution = mock(WorkflowExecution.class);
        given(execution.getTriggerData()).willReturn(null);

        assertThat(service.decryptTriggerData(execution)).isEmpty();
    }

    @Test
    @DisplayName("복호에 실패하면 CustomException으로 알린다(손상된 입력으로 실행하지 않는다)")
    void decryptTriggerData_corrupted_throws() {
        WorkflowExecution execution = mock(WorkflowExecution.class);
        given(execution.getTriggerData()).willReturn("not-a-ciphertext");

        assertThatThrownBy(() -> service.decryptTriggerData(execution))
            .isInstanceOf(CustomException.class);
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
