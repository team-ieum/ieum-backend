package com.ieum.api.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ieum.api.workflow.queue.ExecutionJobQueue;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.engine.SyncExecutionRuntime;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WorkflowExecutionRunnerTest {

    private SyncExecutionRuntime syncExecutionRuntime;
    private WorkflowExecutionService workflowExecutionService;
    private ExecutionJobQueue executionJobQueue;
    private WorkflowExecutionRunner runner;

    private final WorkflowVersion version = Mockito.mock(WorkflowVersion.class);
    private final UUID executionId = UUID.randomUUID();
    private final Map<String, Object> triggerData = Map.of("name", "홍길동");

    @BeforeEach
    void setUp() {
        syncExecutionRuntime = Mockito.mock(SyncExecutionRuntime.class);
        workflowExecutionService = Mockito.mock(WorkflowExecutionService.class);
        executionJobQueue = Mockito.mock(ExecutionJobQueue.class);

        runner = new WorkflowExecutionRunner(
            syncExecutionRuntime, workflowExecutionService, executionJobQueue);
    }

    @Test
    @DisplayName("큐 발행에 성공하면 직접 실행하지 않는다 — 실행은 워커가 맡는다")
    void run_enqueued_doesNotExecuteInline() throws Exception {
        when(executionJobQueue.enqueue(executionId)).thenReturn(true);

        runner.run(version, executionId, triggerData);

        verify(syncExecutionRuntime, never()).execute(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Redis 장애로 큐 발행이 실패하면 폴백으로 직접 실행한다")
    void run_enqueueFailed_executesInline() throws Exception {
        when(executionJobQueue.enqueue(executionId)).thenReturn(false);

        runner.run(version, executionId, triggerData);

        verify(syncExecutionRuntime).execute(version, executionId, triggerData, Map.of());
        verify(workflowExecutionService, never()).markAsFailed(any(), any());
    }

    @Test
    @DisplayName("일반 실행은 스킵 대상이 비어 있어 모든 노드를 실행한다(재처리 회귀 방지)")
    void executeNow_normalExecution_passesNoPreCompletedOutputs() throws Exception {
        when(workflowExecutionService.loadReusableNodeOutputs(executionId)).thenReturn(Map.of());

        runner.executeNow(version, executionId, triggerData);

        verify(syncExecutionRuntime).execute(version, executionId, triggerData, Map.of());
    }

    @Test
    @DisplayName("재처리 실행은 원 실행의 성공 노드 출력을 런타임에 넘긴다")
    void executeNow_retryExecution_passesPreCompletedOutputs() throws Exception {
        Map<String, Map<String, Object>> reusable = Map.of("node-1", Map.of("output", "hi"));
        when(workflowExecutionService.loadReusableNodeOutputs(executionId)).thenReturn(reusable);

        runner.executeNow(version, executionId, triggerData);

        verify(syncExecutionRuntime).execute(version, executionId, triggerData, reusable);
    }

    @Test
    @DisplayName("실행 중 예외가 나면 실행을 FAILED로 기록한다")
    void executeNow_runtimeThrows_marksFailed() throws Exception {
        doThrow(new IllegalStateException("boom"))
            .when(syncExecutionRuntime).execute(any(), any(), any(), any());

        runner.executeNow(version, executionId, triggerData);

        verify(workflowExecutionService).markAsFailed(eq(executionId), any());
    }
}
