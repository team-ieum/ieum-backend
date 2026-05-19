package com.ieum.workflowcore.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.SyncExecutionRuntime;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;

@ExtendWith(MockitoExtension.class)
class WorkflowScheduleJobTest {

    @Mock private WorkflowCrudService workflowCrudService;
    @Mock private WorkflowExecutionService workflowExecutionService;
    @Mock private SyncExecutionRuntime syncExecutionRuntime;
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
        given(execution.getId()).willReturn(executionId);

        JobDataMap dataMap = new JobDataMap();
        dataMap.put(WorkflowScheduleJob.KEY_WORKFLOW_ID, workflowId.toString());

        given(jobContext.getJobDetail()).willReturn(jobDetail);
        given(jobDetail.getJobDataMap()).willReturn(dataMap);
    }

    @Test
    @DisplayName("활성 워크플로우 — 정상 실행 (prepareExecution + runtime.execute 호출)")
    void execute_activeWorkflow_executesNormally() throws Exception {
        given(workflowCrudService.findActiveById(workflowId)).willReturn(workflow);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(workflowVersion));
        given(workflowExecutionService.prepareExecution(workflow, workflowVersion, TriggerType.SCHEDULE))
            .willReturn(execution);

        job.execute(jobContext);

        then(workflowExecutionService).should().prepareExecution(workflow, workflowVersion, TriggerType.SCHEDULE);
        then(syncExecutionRuntime).should().execute(eq(workflowVersion), eq(executionId), eq(Collections.emptyMap()));
    }

    @Test
    @DisplayName("비활성 워크플로우 — 실행 스킵 (prepareExecution 미호출)")
    void execute_inactiveWorkflow_skipsExecution() throws Exception {
        given(workflowCrudService.findActiveById(workflowId)).willReturn(null);

        job.execute(jobContext);

        then(workflowExecutionService).should(never()).prepareExecution(any(), any(), any());
        then(syncExecutionRuntime).should(never()).execute(any(), any(UUID.class), any(Map.class));
    }
}
