package com.ieum.workflowcore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
import com.ieum.workflowcore.engine.executor.AlertNotifier;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import jakarta.persistence.LockModeType;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionServiceTest {

    // AesEncryptorTest와 동일한 32바이트 테스트 키 — 실제 암/복호화를 태워 round-trip을 검증한다.
    private static final String TEST_AES_KEY = "ieum-test-secret-key-32bytes-ok!";

    @Mock private WorkflowExecutionRepository workflowExecutionRepository;
    @Mock private WorkflowExecutionLogRepository workflowExecutionLogRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Spy private AesEncryptor aesEncryptor = newAesEncryptor();
    @Mock private AlertNotifier alertNotifier;
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
    @DisplayName("비어 있지 않은 triggerData의 암호화가 실패하면 실행 준비 자체를 실패시킨다(입력이 사라진 채 SUCCESS 방지)")
    void prepareExecution_encryptionFails_failsFast() throws Exception {
        Workflow workflow = mock(Workflow.class);
        given(workflow.isActive()).willReturn(true);
        WorkflowVersion version = mock(WorkflowVersion.class);
        given(objectMapper.writeValueAsString(any()))
            .willThrow(new com.fasterxml.jackson.core.JsonProcessingException("boom") {});

        assertThatThrownBy(() -> service.prepareExecution(
            workflow, version, TriggerType.WEBHOOK, Map.of("city", "Seoul")))
            .isInstanceOf(CustomException.class);

        verify(workflowExecutionRepository, never()).save(any());
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

    @Test
    @DisplayName("재처리용 조회는 잠금 조회를 먼저 태운 뒤 버전을 로딩한다")
    void findForRetry_locksRowBeforeLoadingVersion() throws Exception {
        UUID executionId = UUID.randomUUID();
        WorkflowExecution execution = mock(WorkflowExecution.class);
        given(workflowExecutionRepository.findByIdForUpdate(executionId))
            .willReturn(Optional.of(execution));
        given(workflowExecutionRepository.findWithVersionById(executionId))
            .willReturn(Optional.of(execution));

        assertThat(service.lockExecutionWithVersion(executionId)).isSameAs(execution);

        // 순서가 중요하다 — 잠그지 않고 먼저 읽으면 뒤이은 락 조회가 stale 인스턴스를 덮어쓰지 않는다.
        InOrder order = inOrder(workflowExecutionRepository);
        order.verify(workflowExecutionRepository).findByIdForUpdate(executionId);
        order.verify(workflowExecutionRepository).findWithVersionById(executionId);

        // 락이 실제로 PESSIMISTIC_WRITE인지 — 어노테이션이 빠지면 조회는 성공하고 가드만 조용히 뚫린다.
        Lock lock = WorkflowExecutionRepository.class
            .getMethod("findByIdForUpdate", UUID.class).getAnnotation(Lock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    @DisplayName("재처리가 아닌 실행은 재사용 output이 비어 있어 모든 노드를 실행한다")
    void reusableOutputs_notRetry_returnsEmpty() {
        UUID executionId = UUID.randomUUID();
        given(workflowExecutionRepository.findByRetriedByExecutionId(executionId))
            .willReturn(Optional.empty());

        assertThat(service.loadReusableNodeOutputs(executionId)).isEmpty();
        verify(workflowExecutionLogRepository, never())
            .findByExecutionIdAndStatusIn(any(), any());
    }

    @Test
    @DisplayName("재처리 실행은 원 실행의 SUCCESS·SKIPPED 노드 output을 nodeId별로 되돌린다")
    void reusableOutputs_retry_returnsSucceededAndSkippedByNodeId() {
        UUID sourceId = UUID.randomUUID();
        UUID retryId = UUID.randomUUID();

        WorkflowExecution source = mock(WorkflowExecution.class);
        given(source.getId()).willReturn(sourceId);
        given(workflowExecutionRepository.findByRetriedByExecutionId(retryId))
            .willReturn(Optional.of(source));

        List<WorkflowExecutionLog> sourceLogs = List.of(
            mockLogWithOutput("node-1", "{\"text\":\"안녕\"}"),
            mockLogWithOutput("node-2", "{\"count\":3}"),
            mockLogWithOutput("node-3", null),           // output 없음 → 제외
            mockLogWithOutput("node-4", "{깨진 json"));   // 파싱 실패 → 그 노드만 제외
        // 앞선 재처리에서 건너뛴 노드(SKIPPED)도 다시 살려 실행하지 않도록 함께 읽는다.
        given(workflowExecutionLogRepository.findByExecutionIdAndStatusIn(sourceId,
                List.of(ExecutionLogStatus.SUCCESS, ExecutionLogStatus.SKIPPED)))
            .willReturn(sourceLogs);

        Map<String, Map<String, Object>> outputs = service.loadReusableNodeOutputs(retryId);

        assertThat(outputs).containsOnlyKeys("node-1", "node-2");
        assertThat(outputs.get("node-1")).containsEntry("text", "안녕");
        assertThat(outputs.get("node-2")).containsEntry("count", 3);
    }

    private WorkflowExecutionLog mockLogWithOutput(String nodeId, String outputJson) {
        WorkflowExecutionLog log = mock(WorkflowExecutionLog.class);
        given(log.getOutputJson()).willReturn(outputJson);
        if (outputJson != null) {
            lenient().when(log.getNodeId()).thenReturn(nodeId);
        }
        return log;
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

    @Test
    @DisplayName("markAsFailed가 상태를 전이시키면 실패 알림을 발신한다")
    void markAsFailed_statusTransitioned_sendsAlert() {
        UUID executionId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        Workflow workflow = mock(Workflow.class);
        given(workflow.getId()).willReturn(workflowId);
        given(workflow.getName()).willReturn("실패한 워크플로우");
        given(workflow.getUserId()).willReturn(ownerId);
        WorkflowExecution execution = mock(WorkflowExecution.class);
        given(execution.getStatus()).willReturn(ExecutionStatus.RUNNING);
        given(execution.getId()).willReturn(executionId);
        given(execution.getWorkflow()).willReturn(workflow);
        given(workflowExecutionRepository.findById(executionId)).willReturn(Optional.of(execution));

        service.markAsFailed(executionId, "trigger_data 복호 실패");

        verify(execution).fail();
        ArgumentCaptor<AlertNotifier.ExecutionFailureAlert> captor =
            ArgumentCaptor.forClass(AlertNotifier.ExecutionFailureAlert.class);
        verify(alertNotifier).notifyExecutionFailed(captor.capture());
        AlertNotifier.ExecutionFailureAlert alert = captor.getValue();
        assertThat(alert.executionId()).isEqualTo(executionId);
        assertThat(alert.workflowId()).isEqualTo(workflowId);
        assertThat(alert.workflowName()).isEqualTo("실패한 워크플로우");
        assertThat(alert.ownerUserId()).isEqualTo(ownerId);
        assertThat(alert.errorSummary()).isEqualTo("trigger_data 복호 실패");
        // 이 경로는 실패 노드를 특정할 수 없다
        assertThat(alert.failedNodeId()).isNull();
        assertThat(alert.retryExhausted()).isFalse();
    }

    @Test
    @DisplayName("markAsFailed는 이미 FAILED인 실행에 알림을 다시 보내지 않는다 (런타임이 이미 발신)")
    void markAsFailed_alreadyFailed_skipsAlert() {
        UUID executionId = UUID.randomUUID();
        WorkflowExecution execution = mock(WorkflowExecution.class);
        given(execution.getStatus()).willReturn(ExecutionStatus.FAILED);
        given(workflowExecutionRepository.findById(executionId)).willReturn(Optional.of(execution));

        service.markAsFailed(executionId, "무시되어야 한다");

        verify(execution, never()).fail();
        verify(alertNotifier, never()).notifyExecutionFailed(any());
    }

    @Test
    @DisplayName("markAsFailed의 알림은 트랜잭션 커밋 이후에 발신된다 — 커밋 전엔 발신하지 않는다")
    void markAsFailed_sendsAlertAfterCommit() {
        UUID executionId = UUID.randomUUID();
        Workflow workflow = mock(Workflow.class);
        WorkflowExecution execution = mock(WorkflowExecution.class);
        given(execution.getStatus()).willReturn(ExecutionStatus.RUNNING);
        given(execution.getWorkflow()).willReturn(workflow);
        given(workflowExecutionRepository.findById(executionId)).willReturn(Optional.of(execution));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.markAsFailed(executionId, "trigger_data 복호 실패");

            // 상태 전이는 끝났지만 아직 커밋 전 — Discord POST가 DB 커넥션을 쥔 채 돌면 안 된다.
            verify(execution).fail();
            verify(alertNotifier, never()).notifyExecutionFailed(any());

            TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

            verify(alertNotifier).notifyExecutionFailed(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 markAsFailed의 알림은 발신되지 않는다 (유령 알림 방지)")
    void markAsFailed_rolledBack_skipsAlert() {
        UUID executionId = UUID.randomUUID();
        Workflow workflow = mock(Workflow.class);
        WorkflowExecution execution = mock(WorkflowExecution.class);
        given(execution.getStatus()).willReturn(ExecutionStatus.RUNNING);
        given(execution.getWorkflow()).willReturn(workflow);
        given(workflowExecutionRepository.findById(executionId)).willReturn(Optional.of(execution));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.markAsFailed(executionId, "원인");

            // 롤백 = afterCommit이 불리지 않는다. afterCompletion만 돈다.
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            verify(alertNotifier, never()).notifyExecutionFailed(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("알림 발신이 예외를 던져도 markAsFailed의 상태 전이는 정상 완료된다")
    void markAsFailed_alertThrows_statusTransitionSucceeds() {
        UUID executionId = UUID.randomUUID();
        Workflow workflow = mock(Workflow.class);
        WorkflowExecution execution = mock(WorkflowExecution.class);
        given(execution.getStatus()).willReturn(ExecutionStatus.RUNNING);
        given(execution.getWorkflow()).willReturn(workflow);
        given(workflowExecutionRepository.findById(executionId)).willReturn(Optional.of(execution));
        doThrow(new RuntimeException("discord down"))
            .when(alertNotifier).notifyExecutionFailed(any());

        service.markAsFailed(executionId, "원인");

        verify(execution).fail();
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
