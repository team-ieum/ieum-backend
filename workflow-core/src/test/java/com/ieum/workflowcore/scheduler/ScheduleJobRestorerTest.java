package com.ieum.workflowcore.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

@ExtendWith(MockitoExtension.class)
class ScheduleJobRestorerTest {

    @Mock private WorkflowCrudService workflowCrudService;
    @Mock private WorkflowScheduler workflowScheduler;
    @Mock private ApplicationArguments args;
    @Mock private Workflow workflow1;
    @Mock private Workflow workflow2;

    @InjectMocks
    private ScheduleJobRestorer restorer;

    @Test
    @DisplayName("활성 SCHEDULE 워크플로우 2개 — 모두 registerJob 호출")
    void run_복구_대상_2개_전부_등록() throws Exception {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        given(workflow1.getId()).willReturn(id1);
        given(workflow1.getCronExpression()).willReturn("0 0 10 * * ?");
        given(workflow2.getId()).willReturn(id2);
        given(workflow2.getCronExpression()).willReturn("0 0 18 * * ?");
        given(workflowCrudService.findActiveScheduleWorkflows()).willReturn(List.of(workflow1, workflow2));

        restorer.run(args);

        then(workflowScheduler).should().registerJob(id1, "0 0 10 * * ?");
        then(workflowScheduler).should().registerJob(id2, "0 0 18 * * ?");
    }

    @Test
    @DisplayName("복구 대상 없음 — registerJob 미호출")
    void run_복구_대상_없음() throws Exception {
        given(workflowCrudService.findActiveScheduleWorkflows()).willReturn(Collections.emptyList());

        restorer.run(args);

        then(workflowScheduler).should(never()).registerJob(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("일부 Job 복구 실패 — 나머지 계속 처리 (예외 전파 안 됨)")
    void run_일부_실패해도_나머지_계속() throws Exception {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        given(workflow1.getId()).willReturn(id1);
        given(workflow1.getCronExpression()).willReturn("0 0 10 * * ?");
        given(workflow2.getId()).willReturn(id2);
        given(workflow2.getCronExpression()).willReturn("0 0 18 * * ?");
        given(workflowCrudService.findActiveScheduleWorkflows()).willReturn(List.of(workflow1, workflow2));

        willThrow(new IllegalStateException("스케줄러 오류"))
            .given(workflowScheduler).registerJob(id1, "0 0 10 * * ?");

        // 예외 전파 없이 정상 종료되어야 함
        restorer.run(args);

        // 두 번째 Job은 정상 등록되어야 함
        then(workflowScheduler).should(times(1)).registerJob(id2, "0 0 18 * * ?");
    }
}
