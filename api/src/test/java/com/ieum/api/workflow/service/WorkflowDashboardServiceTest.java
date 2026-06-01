package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.ieum.api.workflow.dto.WorkflowDashboardSummaryResponse;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.repository.WorkflowQueryRepository;
import com.querydsl.core.Tuple;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowDashboardServiceTest {

    @Mock
    private WorkflowQueryRepository queryRepository;

    @InjectMocks
    private WorkflowDashboardService dashboardService;

    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("대시보드 종합 요약 조회 성공 — 통계 및 시간별 집계 정합성 확인")
    void getDashboardSummary_success() {
        // given
        LocalDateTime now = LocalDateTime.now();

        // 어제 실행 수는 5회, 오늘 실행 수는 10회 (증감률 +100.0%)
        given(queryRepository.countExecutionsInPeriod(eq(userId), any(LocalDateTime.class), any(LocalDateTime.class)))
            .willAnswer(invocation -> {
                LocalDateTime start = invocation.getArgument(1);
                if (start.toLocalDate().isBefore(java.time.LocalDate.now())) {
                    return 5L; // 어제 실행 횟수
                }
                return 10L; // 오늘 실행 횟수
            });

        // 오늘 완료된 실행들 설정
        Workflow workflow = Workflow.builder()
            .userId(userId)
            .name("테스트 워크플로우")
            .isActive(true)
            .build();

        WorkflowExecution successExec = WorkflowExecution.builder()
            .workflow(workflow)
            .status(ExecutionStatus.SUCCESS)
            .startedAt(now.minusSeconds(10))
            .build();
        successExec.complete(); // finishedAt = now

        WorkflowExecution failedExec = WorkflowExecution.builder()
            .workflow(workflow)
            .status(ExecutionStatus.FAILED)
            .startedAt(now.minusSeconds(20))
            .build();
        failedExec.fail(); // finishedAt = now

        given(queryRepository.findExecutionsInPeriod(eq(userId), any(), any()))
            .willReturn(List.of(successExec, failedExec));

        // 시간별 데이터 설정 (오늘 10시 2회, 14시 5회)
        Tuple t1 = mockTuple(10, 2L);
        Tuple t2 = mockTuple(14, 5L);
        given(queryRepository.findHourlyExecutionCounts(eq(userId), any(), any()))
            .willReturn(List.of(t1, t2));

        // 워크플로우 상태 설정
        given(queryRepository.countWorkflowsByActiveStatus(userId, true)).willReturn(8L);
        given(queryRepository.countWorkflowsByActiveStatus(userId, false)).willReturn(3L);
        given(queryRepository.countErroredWorkflows(userId)).willReturn(1L);
        given(queryRepository.countRunningExecutions(userId)).willReturn(2L);

        // when
        WorkflowDashboardSummaryResponse response = dashboardService.getDashboardSummary(userId);

        // then
        assertThat(response).isNotNull();
        
        // 1. Metrics 검증
        assertThat(response.getMetrics().getTodayRuns()).isEqualTo(10L);
        assertThat(response.getMetrics().getPercentageChange()).isEqualTo(100.0);
        // 소요 시간: successExec(10s), failedExec(20s) -> 평균 15.0초
        assertThat(response.getMetrics().getAverageDurationSeconds()).isEqualTo(15.0);
        // 성공률: 2건 중 1건 성공 -> 50.0%
        assertThat(response.getMetrics().getSuccessRate()).isEqualTo(50.0);

        // 2. 시간별 24시간 그래프 데이터 검증
        assertThat(response.getHourlyCounts()).hasSize(24);
        assertThat(response.getHourlyCounts().get(10)).isEqualTo(2L);
        assertThat(response.getHourlyCounts().get(14)).isEqualTo(5L);
        assertThat(response.getHourlyCounts().get(0)).isEqualTo(0L);

        // 3. 워크플로우 현황 카드 데이터 검증
        assertThat(response.getWorkflowStats().getTotal()).isEqualTo(11L); // active 8 + inactive 3
        assertThat(response.getWorkflowStats().getActive()).isEqualTo(8L);
        assertThat(response.getWorkflowStats().getInactive()).isEqualTo(3L);
        assertThat(response.getWorkflowStats().getErrored()).isEqualTo(1L);
        assertThat(response.getWorkflowStats().getRunning()).isEqualTo(2L);
    }

    private java.time.LocalDate LocalDate = java.time.LocalDate.now();

    private Tuple mockTuple(Integer hour, Long count) {
        Tuple tuple = org.mockito.Mockito.mock(Tuple.class);
        given(tuple.get(0, Integer.class)).willReturn(hour);
        given(tuple.get(1, Long.class)).willReturn(count);
        return tuple;
    }
}
