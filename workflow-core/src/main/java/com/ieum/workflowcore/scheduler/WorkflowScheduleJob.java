package com.ieum.workflowcore.scheduler;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.SyncExecutionRuntime;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.Collections;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Quartz 스케줄 트리거 Job.
 *
 * <p>Cron 표현식에 따라 주기적으로 실행되며, 지정된 워크플로우를 {@code SCHEDULE} 트리거로 기동한다.
 * {@link DisallowConcurrentExecution}으로 이전 실행이 완료되지 않으면 다음 실행을 스킵한다.
 *
 * <p>Spring Bean이 아니므로 {@link com.ieum.workflowcore.config.QuartzJobFactory}가
 * {@code @Autowired} 필드를 주입한다.
 *
 * <p>JobDataMap 필수 키:
 * <ul>
 *   <li>{@code workflowId} — UUID 문자열
 * </ul>
 */
@Slf4j
@DisallowConcurrentExecution
public class WorkflowScheduleJob implements Job {

    static final String KEY_WORKFLOW_ID = "workflowId";

    @Autowired
    private WorkflowCrudService workflowCrudService;

    @Autowired
    private WorkflowExecutionService workflowExecutionService;

    @Autowired
    private SyncExecutionRuntime syncExecutionRuntime;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String workflowIdStr = context.getJobDetail().getJobDataMap().getString(KEY_WORKFLOW_ID);
        UUID workflowId = UUID.fromString(workflowIdStr);
        log.info("[WorkflowScheduleJob] 스케줄 트리거 시작 — workflowId: {}", workflowId);

        try {
            // 활성 상태 재확인 (비활성화된 경우 스킵)
            Workflow workflow = workflowCrudService.findActiveById(workflowId);
            if (workflow == null) {
                log.warn("[WorkflowScheduleJob] 비활성 워크플로우 스킵 — workflowId: {}", workflowId);
                return;
            }

            WorkflowVersion latestVersion = workflowCrudService.findLatestVersion(workflowId)
                .orElseThrow(() -> new IllegalStateException(
                    "버전이 없는 워크플로우 — workflowId: " + workflowId));

            WorkflowExecution execution = workflowExecutionService.prepareExecution(
                workflow, latestVersion, TriggerType.SCHEDULE);

            syncExecutionRuntime.execute(latestVersion, execution, Collections.emptyMap());

            log.info("[WorkflowScheduleJob] 스케줄 트리거 완료 — workflowId: {}, executionId: {}",
                workflowId, execution.getId());

        } catch (Exception e) {
            log.error("[WorkflowScheduleJob] 실행 실패 — workflowId: {}", workflowId, e);
            throw new JobExecutionException(e);
        }
    }
}
