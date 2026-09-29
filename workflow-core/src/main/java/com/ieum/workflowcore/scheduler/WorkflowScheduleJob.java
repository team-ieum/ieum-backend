package com.ieum.workflowcore.scheduler;

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
 *
 * <p>Spring Bean이 아니므로 {@link com.ieum.workflowcore.config.QuartzJobFactory}가
 * {@code @Autowired} 필드를 주입한다.
 *
 * <p><b>실행은 {@link ExecutionJobEnqueuer}로 api의 잡 큐에 넣는다</b> — 수동·웹훅·재처리와 같은
 * 경로라 실행 도중 프로세스가 죽어도 재시작 후 회수된다. 큐 투입이 실패하면(Redis 장애, Stub)
 * 유실되지 않게 {@code SyncExecutionRuntime}을 직접 부른다(이 경우만 내구성 없음).
 *
 * <p><b>겹침 방지</b> — 잡이 큐에 넣고 바로 반환하므로 {@link DisallowConcurrentExecution}만으로는
 * "이전 실행이 끝나지 않았으면 스킵"이 성립하지 않는다. 그래서 끝나지 않은(PENDING/RUNNING) SCHEDULE
 * 실행이 있으면 실행 레코드를 만들기 전에 스킵한다. 판정 하한은 stuck 임계
 * ({@code workflow.execution.stuck.threshold})다 — 준비만 되고 버려진 고아 PENDING은 sweeper가
 * 정리하지 않으므로, 하한이 없으면 그 스케줄이 영구히 멈춘다.
 * {@link DisallowConcurrentExecution}은 여전히 필요하다 — 같은 JobKey(워크플로우당 1개) 발화를
 * 직렬화해 판정과 레코드 생성 사이에 다른 발화가 끼지 않게 한다.
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

    @Autowired
    private ExecutionJobEnqueuer executionJobEnqueuer;

    @Autowired
    private StuckExecutionProperties stuckExecutionProperties;

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

            LocalDateTime startedAfter = LocalDateTime.now().minus(stuckExecutionProperties.getThreshold());
            if (workflowExecutionService.hasUnfinishedScheduleRun(workflow, startedAfter)) {
                log.info("[WorkflowScheduleJob] 이전 스케줄 실행이 끝나지 않아 스킵 — workflowId: {}", workflowId);
                return;
            }

            WorkflowVersion latestVersion = workflowCrudService.findLatestVersion(workflowId)
                .orElseThrow(() -> new IllegalStateException(
                    "버전이 없는 워크플로우 — workflowId: " + workflowId));

            WorkflowExecution execution = workflowExecutionService.prepareExecution(
                workflow, latestVersion, TriggerType.SCHEDULE, Collections.emptyMap());

            if (!executionJobEnqueuer.enqueue(execution.getId())) {
                log.warn("[WorkflowScheduleJob] 큐 우회 직접 실행 — 프로세스가 죽으면 이 실행은 복구되지 않는다. "
                    + "executionId: {}", execution.getId());
                syncExecutionRuntime.execute(latestVersion, execution.getId(), Collections.emptyMap());
            }

            log.info("[WorkflowScheduleJob] 스케줄 트리거 완료 — workflowId: {}, executionId: {}",
                workflowId, execution.getId());

        } catch (Exception e) {
            log.error("[WorkflowScheduleJob] 실행 실패 — workflowId: {}", workflowId, e);
            throw new JobExecutionException(e);
        }
    }
}
