package com.ieum.api.workflow.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.engine.SyncExecutionRuntime;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class ExecutionJobWorkerTest {

    private static final RecordId RECORD_ID = RecordId.of("1-0");

    private StringRedisTemplate redisTemplate;
    private StreamOperations<String, String, String> streamOperations;
    private WorkflowExecutionRepository workflowExecutionRepository;
    private WorkflowExecutionService workflowExecutionService;
    private SyncExecutionRuntime syncExecutionRuntime;
    private ExecutionJobWorker worker;

    private final UUID executionId = UUID.randomUUID();
    private final WorkflowVersion version = Mockito.mock(WorkflowVersion.class);

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        streamOperations = Mockito.mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);

        workflowExecutionRepository = Mockito.mock(WorkflowExecutionRepository.class);
        workflowExecutionService = Mockito.mock(WorkflowExecutionService.class);
        syncExecutionRuntime = Mockito.mock(SyncExecutionRuntime.class);

        worker = newWorker(Runnable::run);
    }

    private ExecutionJobWorker newWorker(Executor executor) {
        // 러너는 실물을 쓴다 — 큐 → 워커 → SyncExecutionRuntime.execute 경로를 통째로 검증하기 위함.
        WorkflowExecutionRunner runner = new WorkflowExecutionRunner(
            syncExecutionRuntime, workflowExecutionService, Mockito.mock(ExecutionJobQueue.class));
        return new ExecutionJobWorker(redisTemplate, workflowExecutionRepository,
            workflowExecutionService, runner, executor);
    }

    private MapRecord<String, String, String> jobRecord(String executionIdValue) {
        return StreamRecords.mapBacked(
                Map.of(ExecutionJobQueue.FIELD_EXECUTION_ID, executionIdValue))
            .withId(RECORD_ID)
            .withStreamKey(ExecutionJobQueue.STREAM_KEY);
    }

    private WorkflowExecution execution(ExecutionStatus status) {
        WorkflowExecution execution = Mockito.mock(WorkflowExecution.class);
        when(execution.getStatus()).thenReturn(status);
        return execution;
    }

    @Test
    @DisplayName("잡을 소비하면 DB에서 복호한 트리거 입력으로 실행하고, 실행이 끝난 뒤에 ack한다")
    void onMessage_runsExecutionThenAcknowledges() throws Exception {
        WorkflowExecution execution = execution(ExecutionStatus.PENDING);
        when(execution.getWorkflowVersion()).thenReturn(version);
        when(workflowExecutionRepository.findWithVersionById(executionId))
            .thenReturn(Optional.of(execution));
        when(workflowExecutionService.decryptTriggerData(execution))
            .thenReturn(Map.of("name", "홍길동"));

        worker.onMessage(jobRecord(executionId.toString()));

        InOrder inOrder = Mockito.inOrder(syncExecutionRuntime, streamOperations);
        inOrder.verify(syncExecutionRuntime).execute(version, executionId, Map.of("name", "홍길동"), Map.of());
        inOrder.verify(streamOperations).acknowledge(
            ExecutionJobQueue.STREAM_KEY, ExecutionJobQueue.GROUP, RECORD_ID);
    }

    @Test
    @DisplayName("회수한 잡의 실행이 이미 SUCCESS면 재실행하지 않고 ack만 한다")
    void onMessage_alreadySucceeded_acknowledgesWithoutRerun() throws Exception {
        WorkflowExecution execution = execution(ExecutionStatus.SUCCESS);
        when(workflowExecutionRepository.findWithVersionById(executionId))
            .thenReturn(Optional.of(execution));

        worker.onMessage(jobRecord(executionId.toString()));

        verify(syncExecutionRuntime, never()).execute(any(), any(), any(), any());
        verify(streamOperations).acknowledge(
            ExecutionJobQueue.STREAM_KEY, ExecutionJobQueue.GROUP, RECORD_ID);
    }

    @Test
    @DisplayName("회수한 잡의 실행이 이미 FAILED면 재실행하지 않고 ack만 한다")
    void onMessage_alreadyFailed_acknowledgesWithoutRerun() throws Exception {
        WorkflowExecution execution = execution(ExecutionStatus.FAILED);
        when(workflowExecutionRepository.findWithVersionById(executionId))
            .thenReturn(Optional.of(execution));

        worker.onMessage(jobRecord(executionId.toString()));

        verify(syncExecutionRuntime, never()).execute(any(), any(), any(), any());
        verify(streamOperations).acknowledge(
            ExecutionJobQueue.STREAM_KEY, ExecutionJobQueue.GROUP, RECORD_ID);
    }

    @Test
    @DisplayName("실행 레코드가 없으면 잡을 폐기(ack)한다")
    void onMessage_executionMissing_acknowledgesAndDrops() throws Exception {
        when(workflowExecutionRepository.findWithVersionById(executionId))
            .thenReturn(Optional.empty());

        worker.onMessage(jobRecord(executionId.toString()));

        verify(syncExecutionRuntime, never()).execute(any(), any(), any(), any());
        verify(streamOperations).acknowledge(
            ExecutionJobQueue.STREAM_KEY, ExecutionJobQueue.GROUP, RECORD_ID);
    }

    @Test
    @DisplayName("트리거 입력 복호에 실패하면 실행을 FAILED로 기록하고 ack한다(무한 재배달 방지)")
    void onMessage_decryptFailure_marksFailedAndAcknowledges() throws Exception {
        WorkflowExecution execution = execution(ExecutionStatus.PENDING);
        when(workflowExecutionRepository.findWithVersionById(executionId))
            .thenReturn(Optional.of(execution));
        when(workflowExecutionService.decryptTriggerData(execution))
            .thenThrow(new CustomException(ErrorCode.CREDENTIAL_DECRYPT_FAILED));

        worker.onMessage(jobRecord(executionId.toString()));

        verify(syncExecutionRuntime, never()).execute(any(), any(), any(), any());
        verify(workflowExecutionService).markAsFailed(eq(executionId), any());
        verify(streamOperations).acknowledge(
            ExecutionJobQueue.STREAM_KEY, ExecutionJobQueue.GROUP, RECORD_ID);
    }

    @Test
    @DisplayName("실행 중인 잡이 회수돼 재배달되면 재실행하지 않고, ack도 하지 않는다(원본이 끝날 때 ack한다)")
    void onMessage_redeliveredWhileRunning_doesNotRunTwice() throws Exception {
        WorkflowExecution execution = execution(ExecutionStatus.PENDING);
        when(execution.getWorkflowVersion()).thenReturn(version);
        when(workflowExecutionRepository.findWithVersionById(executionId))
            .thenReturn(Optional.of(execution));
        when(workflowExecutionService.decryptTriggerData(execution)).thenReturn(Map.of());

        MapRecord<String, String, String> record = jobRecord(executionId.toString());
        // 실행이 minIdle을 넘겨 주기 회수가 같은 엔트리를 다시 물어오는 상황.
        // 실행 도중(=RUNNING) 재배달이므로 종료 상태 가드로는 안 걸린다.
        Mockito.doAnswer(invocation -> {
            worker.onMessage(record);
            return null;
        }).when(syncExecutionRuntime).execute(any(), any(), any(), any());

        worker.onMessage(record);

        verify(syncExecutionRuntime, Mockito.times(1)).execute(version, executionId, Map.of(), Map.of());
        verify(streamOperations, Mockito.times(1)).acknowledge(
            ExecutionJobQueue.STREAM_KEY, ExecutionJobQueue.GROUP, RECORD_ID);
    }

    @Test
    @DisplayName("실행이 끝나면 in-flight에서 빠져 이후 재배달을 정상 처리한다")
    void onMessage_afterCompletion_handlesRedeliveryAgain() throws Exception {
        WorkflowExecution execution = execution(ExecutionStatus.PENDING);
        when(execution.getWorkflowVersion()).thenReturn(version);
        when(workflowExecutionRepository.findWithVersionById(executionId))
            .thenReturn(Optional.of(execution));
        when(workflowExecutionService.decryptTriggerData(execution)).thenReturn(Map.of());

        worker.onMessage(jobRecord(executionId.toString()));
        worker.onMessage(jobRecord(executionId.toString()));

        verify(syncExecutionRuntime, Mockito.times(2)).execute(version, executionId, Map.of(), Map.of());
    }

    @Test
    @DisplayName("실행 풀이 포화면 잠시 뒤 다시 제출한다 — 폴링 스레드를 곧바로 점유하지 않는다")
    void onMessage_poolSaturated_retriesSubmission() throws Exception {
        WorkflowExecution execution = execution(ExecutionStatus.PENDING);
        when(execution.getWorkflowVersion()).thenReturn(version);
        when(workflowExecutionRepository.findWithVersionById(executionId))
            .thenReturn(Optional.of(execution));
        when(workflowExecutionService.decryptTriggerData(execution)).thenReturn(Map.of());

        AtomicInteger submissions = new AtomicInteger();
        Executor rejectsOnce = task -> {
            if (submissions.incrementAndGet() == 1) {
                throw new TaskRejectedException("pool full");
            }
            task.run();
        };
        worker = newWorker(rejectsOnce);

        worker.onMessage(jobRecord(executionId.toString()));

        assertThat(submissions.get()).isEqualTo(2);
        verify(syncExecutionRuntime).execute(version, executionId, Map.of(), Map.of());
    }

    @Test
    @DisplayName("실행 풀이 계속 포화면 최후수단으로 폴링 스레드에서 인라인 실행한다 — 잡을 잃지 않는다")
    void onMessage_poolAlwaysSaturated_runsInline() throws Exception {
        WorkflowExecution execution = execution(ExecutionStatus.PENDING);
        when(execution.getWorkflowVersion()).thenReturn(version);
        when(workflowExecutionRepository.findWithVersionById(executionId))
            .thenReturn(Optional.of(execution));
        when(workflowExecutionService.decryptTriggerData(execution)).thenReturn(Map.of());

        worker = newWorker(task -> {
            throw new TaskRejectedException("pool full");
        });

        worker.onMessage(jobRecord(executionId.toString()));

        verify(syncExecutionRuntime).execute(version, executionId, Map.of(), Map.of());
        verify(streamOperations).acknowledge(
            ExecutionJobQueue.STREAM_KEY, ExecutionJobQueue.GROUP, RECORD_ID);
    }

    @Test
    @DisplayName("해석 불가 페이로드는 DB 조회 없이 폐기(ack)한다")
    void onMessage_malformedPayload_acknowledgesAndDrops() {
        worker.onMessage(jobRecord("not-a-uuid"));

        verify(workflowExecutionRepository, never()).findWithVersionById(any());
        verify(streamOperations).acknowledge(
            ExecutionJobQueue.STREAM_KEY, ExecutionJobQueue.GROUP, RECORD_ID);
    }
}
