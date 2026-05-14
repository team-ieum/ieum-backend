package com.ieum.workflowcore.scheduler;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 서버 재시작 시 RAMJobStore에서 사라진 Quartz Job을 DB 기준으로 복구한다.
 *
 * <p>RAMJobStore는 JVM 인메모리 기반이므로 서버 재시작 시 모든 Job이 사라진다.
 * 이 Runner는 애플리케이션 기동 직후 활성 상태인 SCHEDULE 워크플로우를 전부 조회하여
 * {@link WorkflowScheduler}에 재등록한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleJobRestorer implements ApplicationRunner {

    private final WorkflowCrudService workflowCrudService;
    private final WorkflowScheduler workflowScheduler;

    @Override
    public void run(ApplicationArguments args) {
        List<Workflow> scheduleWorkflows = workflowCrudService.findActiveScheduleWorkflows();

        if (scheduleWorkflows.isEmpty()) {
            log.info("[ScheduleJobRestorer] 복구할 스케줄 Job 없음");
            return;
        }

        log.info("[ScheduleJobRestorer] 스케줄 Job 복구 시작 — 대상: {}개", scheduleWorkflows.size());

        int successCount = 0;
        for (Workflow workflow : scheduleWorkflows) {
            try {
                workflowScheduler.registerJob(workflow.getId(), workflow.getCronExpression());
                successCount++;
            } catch (Exception e) {
                // 단일 Job 복구 실패가 전체 기동을 막지 않도록 예외를 삼킴
                log.error("[ScheduleJobRestorer] Job 복구 실패 — workflowId: {}", workflow.getId(), e);
            }
        }

        log.info("[ScheduleJobRestorer] 스케줄 Job 복구 완료 — 성공: {}/{}개",
            successCount, scheduleWorkflows.size());
    }
}
