package com.ieum.api.workflow.service;

import com.ieum.api.workflow.dto.WorkflowDashboardErrorResponse;
import com.ieum.api.workflow.dto.WorkflowDashboardExecutionResponse;
import com.ieum.api.workflow.dto.WorkflowDashboardSummaryResponse;
import com.ieum.common.dto.PageResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.repository.WorkflowQueryRepository;
import com.querydsl.core.Tuple;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowDashboardService {

    private final WorkflowQueryRepository workflowQueryRepository;

    /**
     * 대시보드 상단 요약 정보 및 차트 데이터를 조회합니다.
     */
    public WorkflowDashboardSummaryResponse getDashboardSummary(UUID userId) {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfToday = today.atStartOfDay();
        LocalDateTime endOfToday = today.atTime(LocalTime.MAX);

        LocalDate yesterday = today.minusDays(1);
        LocalDateTime startOfYesterday = yesterday.atStartOfDay();
        LocalDateTime endOfYesterday = yesterday.atTime(LocalTime.MAX);

        // 1. 실행 횟수 조회 (오늘 / 어제)
        long todayRuns = workflowQueryRepository.countExecutionsInPeriod(userId, startOfToday, endOfToday);
        long yesterdayRuns = workflowQueryRepository.countExecutionsInPeriod(userId, startOfYesterday, endOfYesterday);

        // 2. 어제 대비 증감률 계산
        double percentageChange = 0.0;
        if (yesterdayRuns > 0) {
            percentageChange = ((double) (todayRuns - yesterdayRuns) / yesterdayRuns) * 100.0;
        } else if (todayRuns > 0) {
            percentageChange = 100.0;
        }

        // 3. 성공률 및 평균 소요 시간 계산 (오늘 실행 완료된 것 기준)
        List<WorkflowExecution> executionsToday = workflowQueryRepository.findExecutionsInPeriod(userId, startOfToday, endOfToday);
        List<WorkflowExecution> finishedToday = executionsToday.stream()
            .filter(e -> e.getStatus() == ExecutionStatus.SUCCESS || e.getStatus() == ExecutionStatus.FAILED)
            .toList();

        long totalFinished = finishedToday.size();
        long successCount = finishedToday.stream()
            .filter(e -> e.getStatus() == ExecutionStatus.SUCCESS)
            .count();

        double successRate = totalFinished > 0 ? ((double) successCount / totalFinished) * 100.0 : 0.0;

        double averageDurationSeconds = finishedToday.stream()
            .filter(e -> e.getStartedAt() != null && e.getFinishedAt() != null)
            .mapToLong(e -> java.time.Duration.between(e.getStartedAt(), e.getFinishedAt()).toMillis())
            .average()
            .orElse(0.0) / 1000.0;

        // 4. 시간대별 실행 횟수 맵 생성 (0~23시 빈 값 0으로 초기화)
        Map<Integer, Long> hourlyCounts = new LinkedHashMap<>();
        for (int h = 0; h < 24; h++) {
            hourlyCounts.put(h, 0L);
        }

        List<Tuple> hourlyTuples = workflowQueryRepository.findHourlyExecutionCounts(userId, startOfToday, endOfToday);
        for (Tuple tuple : hourlyTuples) {
            Integer hour = tuple.get(0, Integer.class);
            Long count = tuple.get(1, Long.class);
            if (hour != null && count != null) {
                hourlyCounts.put(hour, count);
            }
        }

        // 5. 워크플로우 현황 집계
        long activeCount = workflowQueryRepository.countWorkflowsByActiveStatus(userId, true);
        long inactiveCount = workflowQueryRepository.countWorkflowsByActiveStatus(userId, false);
        long erroredCount = workflowQueryRepository.countErroredWorkflows(userId);
        long runningCount = workflowQueryRepository.countRunningExecutions(userId);
        long totalWorkflows = activeCount + inactiveCount;

        return WorkflowDashboardSummaryResponse.builder()
            .metrics(WorkflowDashboardSummaryResponse.MetricsSummary.builder()
                .todayRuns(todayRuns)
                .percentageChange(Math.round(percentageChange * 10.0) / 10.0) // 소수점 첫째자리 반올림
                .averageDurationSeconds(Math.round(averageDurationSeconds * 10.0) / 10.0)
                .successRate(Math.round(successRate * 10.0) / 10.0)
                .build())
            .hourlyCounts(hourlyCounts)
            .workflowStats(WorkflowDashboardSummaryResponse.WorkflowStatsSummary.builder()
                .total(totalWorkflows)
                .active(activeCount)
                .inactive(inactiveCount)
                .errored(erroredCount)
                .running(runningCount)
                .build())
            .build();
    }

    /**
     * 최근 실행 로그 목록을 페이징 조회합니다.
     */
    public PageResponse<WorkflowDashboardExecutionResponse> getRecentExecutions(UUID userId, String cursor, int size) {
        int page = parseCursor(cursor);
        List<WorkflowExecution> executions = workflowQueryRepository.findRecentExecutions(userId, page, size);
        boolean hasNext = workflowQueryRepository.hasNextRecentExecutions(userId, page, size);

        List<WorkflowDashboardExecutionResponse> responses = executions.stream()
            .map(WorkflowDashboardExecutionResponse::from)
            .toList();

        return PageResponse.of(responses, hasNext, hasNext ? String.valueOf(page + 1) : null);
    }

    /**
     * 최근 실패한 에러 로그 목록을 페이징 조회합니다.
     */
    public PageResponse<WorkflowDashboardErrorResponse> getRecentErrors(UUID userId, String cursor, int size) {
        int page = parseCursor(cursor);
        List<Tuple> failedTuples = workflowQueryRepository.findFailedExecutionsWithErrors(userId, page, size);
        boolean hasNext = workflowQueryRepository.hasNextFailedExecutions(userId, page, size);

        List<WorkflowDashboardErrorResponse> responses = failedTuples.stream()
            .map(tuple -> {
                WorkflowExecution execution = tuple.get(0, WorkflowExecution.class);
                String nodeId = tuple.get(1, String.class);
                NodeType nodeType = tuple.get(2, NodeType.class);
                String errorMessage = tuple.get(3, String.class);
                return WorkflowDashboardErrorResponse.of(execution, nodeId, nodeType, errorMessage);
            })
            .toList();

        return PageResponse.of(responses, hasNext, hasNext ? String.valueOf(page + 1) : null);
    }

    private int parseCursor(String cursor) {
        if (cursor == null) return 0;
        try {
            return Integer.parseInt(cursor);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.INVALID_CURSOR);
        }
    }
}
