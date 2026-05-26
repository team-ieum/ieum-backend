package com.ieum.workflowcore.scheduler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerKey;

@ExtendWith(MockitoExtension.class)
class WorkflowSchedulerTest {

    @Mock
    private Scheduler scheduler;

    @InjectMocks
    private WorkflowScheduler workflowScheduler;

    private static final String VALID_CRON = "0 0 10 * * ?";

    // ────────────────────────────────────────────── registerJob

    @Test
    @DisplayName("신규 워크플로우 Job 등록 — scheduleJob 호출")
    void registerJob_new_Job_registration() throws Exception {
        UUID workflowId = UUID.randomUUID();
        JobKey jobKey = JobKeyGenerator.jobKey(workflowId);

        given(scheduler.checkExists(jobKey)).willReturn(false);

        workflowScheduler.registerJob(workflowId, VALID_CRON);

        then(scheduler).should().scheduleJob(any(JobDetail.class), any(CronTrigger.class));
        then(scheduler).should(never()).rescheduleJob(any(TriggerKey.class), any());
    }

    @Test
    @DisplayName("기존 Job 재등록 — rescheduleJob으로 Trigger만 교체")
    void registerJob_existing_Job_re_register() throws Exception {
        UUID workflowId = UUID.randomUUID();
        JobKey jobKey = JobKeyGenerator.jobKey(workflowId);
        TriggerKey triggerKey = JobKeyGenerator.triggerKey(workflowId);

        given(scheduler.checkExists(jobKey)).willReturn(true);

        workflowScheduler.registerJob(workflowId, VALID_CRON);

        then(scheduler).should().rescheduleJob(any(TriggerKey.class), any(CronTrigger.class));
        then(scheduler).should(never()).scheduleJob(any(JobDetail.class), any(CronTrigger.class));
    }

    @Test
    @DisplayName("스케줄러 예외 발생 시 IllegalStateException으로 변환")
    void registerJob_scheduler_exception_conversion() throws Exception {
        UUID workflowId = UUID.randomUUID();
        given(scheduler.checkExists(any(JobKey.class))).willThrow(new SchedulerException("연결 실패"));

        assertThatThrownBy(() -> workflowScheduler.registerJob(workflowId, VALID_CRON))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("스케줄 Job 등록 실패");
    }

    // ────────────────────────────────────────────── deleteJob

    @Test
    @DisplayName("등록된 Job 삭제 — deleteJob 호출")
    void deleteJob_registered_Job_delete() throws Exception {
        UUID workflowId = UUID.randomUUID();
        JobKey jobKey = JobKeyGenerator.jobKey(workflowId);

        given(scheduler.checkExists(jobKey)).willReturn(true);

        workflowScheduler.deleteJob(workflowId);

        then(scheduler).should().deleteJob(jobKey);
    }

    @Test
    @DisplayName("미등록 Job 삭제 요청 — no-op (deleteJob 미호출)")
    void deleteJob_unregisteredJob_noOp() throws Exception {
        UUID workflowId = UUID.randomUUID();
        JobKey jobKey = JobKeyGenerator.jobKey(workflowId);

        given(scheduler.checkExists(jobKey)).willReturn(false);

        workflowScheduler.deleteJob(workflowId);

        then(scheduler).should(never()).deleteJob(any(JobKey.class));
    }

    @Test
    @DisplayName("삭제 중 스케줄러 예외 발생 시 IllegalStateException으로 변환")
    void deleteJob_schedulerException_throwsIllegalState() throws Exception {
        UUID workflowId = UUID.randomUUID();
        given(scheduler.checkExists(any(JobKey.class))).willThrow(new SchedulerException("연결 실패"));

        assertThatThrownBy(() -> workflowScheduler.deleteJob(workflowId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("스케줄 Job 삭제 실패");
    }
}
