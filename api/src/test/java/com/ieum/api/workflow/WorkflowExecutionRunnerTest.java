package com.ieum.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ieum.api.workflow.queue.ExecutionJobQueue;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.engine.SyncExecutionRuntime;
import com.ieum.workflowcore.engine.validation.WorkflowValidationException;
import com.ieum.workflowcore.engine.validation.WorkflowValidationResult;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

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

    @Test
    @DisplayName("검증 차단(사용자 설정 오류)은 ERROR+스택이 아니라 WARN 한 줄로 남기고 FAILED 기록은 그대로다")
    void executeNow_validationBlocked_logsSingleWarn() throws Exception {
        doThrow(new WorkflowValidationException(List.of(
                new WorkflowValidationResult.ValidationError("a", "config.prompt", "REQUIRED", "프롬프트를 입력하세요."))))
            .when(syncExecutionRuntime).execute(any(), any(), any(), any());
        Logger runnerLogger = (Logger) LoggerFactory.getLogger(WorkflowExecutionRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        runnerLogger.addAppender(appender);
        try {
            runner.executeNow(version, executionId, triggerData);
        } finally {
            runnerLogger.detachAppender(appender);
        }

        verify(workflowExecutionService).markAsFailed(eq(executionId), any());
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage())
            .contains(executionId.toString(), "검증 실패 1건: 프롬프트를 입력하세요.");
    }

    @Test
    @DisplayName("검증 외 예외는 기존처럼 ERROR + 스택트레이스로 남는다")
    void executeNow_otherException_logsErrorWithStack() throws Exception {
        doThrow(new IllegalStateException("boom"))
            .when(syncExecutionRuntime).execute(any(), any(), any(), any());
        Logger runnerLogger = (Logger) LoggerFactory.getLogger(WorkflowExecutionRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        runnerLogger.addAppender(appender);
        try {
            runner.executeNow(version, executionId, triggerData);
        } finally {
            runnerLogger.detachAppender(appender);
        }

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
        assertThat(appender.list.get(0).getThrowableProxy()).isNotNull();
    }
}
