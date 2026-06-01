package com.ieum.api.workflow.dto;

import java.util.Map;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WorkflowDashboardSummaryResponse {
    private final MetricsSummary metrics;
    private final Map<Integer, Long> hourlyCounts;
    private final WorkflowStatsSummary workflowStats;

    @Getter
    @Builder
    public static class MetricsSummary {
        private final long todayRuns;
        private final double percentageChange; // 어제 대비 증감률 (예: 12.0)
        private final double averageDurationSeconds; // 오늘 성공한 실행들의 평균 소요 시간 (초)
        private final double successRate; // 오늘 실행 완료된 건 중 성공 비율 (예: 97.8)
    }

    @Getter
    @Builder
    public static class WorkflowStatsSummary {
        private final long total;
        private final long active;
        private final long inactive;
        private final long errored;
        private final long running;
    }
}
