package com.ieum.workflowcore.scheduler;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.stereotype.Component;

/**
 * Quartz 스케줄러 Job 등록/수정/삭제를 담당하는 유틸 컴포넌트.
 *
 * <p>RAMJobStore 기반이므로 서버 재시작 시 Job이 사라진다.
 * 재시작 복구는 {@link ScheduleJobRestorer}가 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowScheduler {

    private final Scheduler scheduler;

    /**
     * 워크플로우 스케줄 Job을 등록하거나 Cron 표현식을 갱신한다.
     *
     * <p>이미 등록된 Job이 있으면 Trigger만 교체(reschedule)하고,
     * 없으면 JobDetail + Trigger를 새로 등록한다.
     *
     * @param workflowId     워크플로우 ID
     * @param cronExpression Quartz 6자리 Cron 표현식 (예: "0 0 10 * * ?")
     */
    public void registerJob(UUID workflowId, String cronExpression) {
        JobKey jobKey = JobKeyGenerator.jobKey(workflowId);
        TriggerKey triggerKey = JobKeyGenerator.triggerKey(workflowId);

        try {
            // withMisfireHandlingInstructionDoNothing: 서버 다운 중 놓친 실행은 스킵
            // (FIRE_ONCE_NOW 대신 사용 — 밀린 실행이 한꺼번에 트리거되는 것을 방지)
            CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey)
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
                    .withMisfireHandlingInstructionDoNothing())
                .build();

            if (scheduler.checkExists(jobKey)) {
                // 이미 등록된 Job — Trigger만 교체
                scheduler.rescheduleJob(triggerKey, trigger);
                log.info("[WorkflowScheduler] Job 재등록 — workflowId: {}, cron: {}", workflowId, cronExpression);
            } else {
                // 신규 등록
                JobDetail jobDetail = JobBuilder.newJob(WorkflowScheduleJob.class)
                    .withIdentity(jobKey)
                    .usingJobData(WorkflowScheduleJob.KEY_WORKFLOW_ID, workflowId.toString())
                    .storeDurably(false)
                    .build();
                scheduler.scheduleJob(jobDetail, trigger);
                log.info("[WorkflowScheduler] Job 신규 등록 — workflowId: {}, cron: {}", workflowId, cronExpression);
            }
        } catch (SchedulerException e) {
            log.error("[WorkflowScheduler] Job 등록 실패 — workflowId: {}", workflowId, e);
            throw new IllegalStateException("스케줄 Job 등록 실패: " + workflowId, e);
        }
    }

    /**
     * 워크플로우 스케줄 Job을 삭제한다.
     *
     * <p>등록되지 않은 Job에 호출해도 안전하다 (no-op).
     *
     * @param workflowId 워크플로우 ID
     */
    public void deleteJob(UUID workflowId) {
        JobKey jobKey = JobKeyGenerator.jobKey(workflowId);
        try {
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("[WorkflowScheduler] Job 삭제 — workflowId: {}", workflowId);
            }
        } catch (SchedulerException e) {
            log.error("[WorkflowScheduler] Job 삭제 실패 — workflowId: {}", workflowId, e);
            throw new IllegalStateException("스케줄 Job 삭제 실패: " + workflowId, e);
        }
    }
}
