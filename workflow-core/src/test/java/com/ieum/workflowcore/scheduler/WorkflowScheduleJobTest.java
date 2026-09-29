package com.ieum.workflowcore.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.ieum.workflowcore.config.StuckExecutionProperties;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.SyncExecutionRuntime;
import com.ieum.workflowcore.engine.executor.ExecutionJobEnqueuer;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;

@ExtendWith(MockitoExtension.class)
class WorkflowScheduleJobTest {

    @Mock private WorkflowCrudService workflowCrudService;
    @Mock private WorkflowExecutionService workflowExecutionService;
    @Mock private SyncExecutionRuntime syncExecutionRuntime;
    @Mock private ExecutionJobEnqueuer executionJobEnqueuer;
    @Spy private StuckExecutionProperties stuckExecutionProperties = new StuckExecutionProperties();
    @Mock private JobExecutionContext jobContext;
    @Mock private JobDetail jobDetail;
    @Mock private Workflow workflow;
    @Mock private WorkflowVersion workflowVersion;
    @Mock private WorkflowExecution execution;

    @InjectMocks
    private WorkflowScheduleJob job;

    private UUID workflowId;
    private UUID executionId;

    @BeforeEach
    void setUp() {
        workflowId = UUID.randomUUID();
        executionId = UUID.randomUUID();

        JobDataMap dataMap = new JobDataMap();
        dataMap.put(WorkflowScheduleJob.KEY_WORKFLOW_ID, workflowId.toString());

        given(jobContext.getJobDetail()).willReturn(jobDetail);
        given(jobDetail.getJobDataMap()).willReturn(dataMap);
    }

    private void givenPreparedExecution() {
        given(workflowCrudService.findActiveById(workflowId)).willReturn(workflow);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(workflowVersion));
        given(workflowExecutionService.prepareExecution(
                workflow, workflowVersion, TriggerType.SCHEDULE, Collections.emptyMap()))
            .willReturn(execution);
        given(execution.getId()).willReturn(executionId);
    }

    @Test
    @DisplayName("큐 투입 성공 — 워커가 실행하므로 직접 실행하지 않는다")
    void execute_enqueued_doesNotRunDirectly() throws Exception {
        givenPreparedExecution();
        given(executionJobEnqueuer.enqueue(executionId)).willReturn(true);

        job.execute(jobContext);

        then(executionJobEnqueuer).should().enqueue(executionId);
        then(syncExecutionRuntime).should(never()).execute(any(), any(UUID.class), any(Map.class));
    }

    @Test
    @DisplayName("큐 투입 실패 — 실행이 유실되지 않게 런타임을 직접 부른다")
    void execute_enqueueFails_fallsBackToDirect() throws Exception {
        givenPreparedExecution();
        given(executionJobEnqueuer.enqueue(executionId)).willReturn(false);

        job.execute(jobContext);

        then(workflowExecutionService).should()
            .prepareExecution(workflow, workflowVersion, TriggerType.SCHEDULE, Collections.emptyMap());
        then(syncExecutionRuntime).should().execute(eq(workflowVersion), eq(executionId), eq(Collections.emptyMap()));
    }

    @Test
    @DisplayName("끝나지 않은 SCHEDULE 실행이 있으면 이번 발화는 실행 레코드조차 만들지 않고 스킵한다")
    void execute_unfinishedScheduleRunExists_skipsWithoutPreparing() throws Exception {
        given(workflowCrudService.findActiveById(workflowId)).willReturn(workflow);
        given(workflowExecutionService.hasUnfinishedScheduleRun(eq(workflow), any(LocalDateTime.class)))
            .willReturn(true);

        job.execute(jobContext);

        then(workflowExecutionService).should(never()).prepareExecution(any(), any(), any(), any());
        then(executionJobEnqueuer).should(never()).enqueue(any());
        then(syncExecutionRuntime).should(never()).execute(any(), any(UUID.class), any(Map.class));
    }

    @Test
    @DisplayName("겹침 판정은 stuck 임계 안에서 시작한 실행만 본다 — 임계 밖 고아 PENDING이 스케줄을 영구히 막지 않는다")
    void execute_overlapCheck_usesStuckThresholdAsLowerBound() throws Exception {
        givenPreparedExecution();
        given(executionJobEnqueuer.enqueue(executionId)).willReturn(true);
        ArgumentCaptor<LocalDateTime> startedAfter = ArgumentCaptor.forClass(LocalDateTime.class);

        job.execute(jobContext);

        then(workflowExecutionService).should().hasUnfinishedScheduleRun(eq(workflow), startedAfter.capture());
        // 3시간 전 준비만 되고 버려진 PENDING(기본 임계 2시간 밖)은 겹침 사유가 아니다.
        assertThat(LocalDateTime.now().minusHours(3)).isBefore(startedAfter.getValue());
        // 40분째 도는 정상 실행은 임계 안이라 겹침으로 본다.
        assertThat(LocalDateTime.now().minusMinutes(40)).isAfter(startedAfter.getValue());
    }

    @Test
    @DisplayName("비활성 워크플로우 — 실행 스킵 (prepareExecution 미호출)")
    void execute_inactiveWorkflow_skipsExecution() throws Exception {
        given(workflowCrudService.findActiveById(workflowId)).willReturn(null);

        job.execute(jobContext);

        then(workflowExecutionService).should(never()).prepareExecution(any(), any(), any(), any());
        then(syncExecutionRuntime).should(never()).execute(any(), any(UUID.class), any(Map.class));
    }
}
