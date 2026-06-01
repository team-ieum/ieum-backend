package com.ieum.api.workflow.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.api.workflow.dto.WorkflowDashboardErrorResponse;
import com.ieum.api.workflow.dto.WorkflowDashboardExecutionResponse;
import com.ieum.api.workflow.dto.WorkflowDashboardSummaryResponse;
import com.ieum.api.workflow.service.WorkflowDashboardService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.PageResponse;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class WorkflowDashboardControllerTest {

    @Mock
    private WorkflowDashboardService dashboardService;

    @InjectMocks
    private WorkflowDashboardController dashboardController;

    private MockMvc mockMvc;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(dashboardController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();

        CustomUserDetails userDetails = CustomUserDetails.of(userId, "user@example.com", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities())
        );
    }

    @Test
    @DisplayName("GET /api/v1/workflows/dashboard/summary — 대시보드 통계 조회 성공")
    void getSummary_success() throws Exception {
        // given
        WorkflowDashboardSummaryResponse response = WorkflowDashboardSummaryResponse.builder()
            .metrics(WorkflowDashboardSummaryResponse.MetricsSummary.builder()
                .todayRuns(1240)
                .percentageChange(12.0)
                .averageDurationSeconds(1.6)
                .successRate(97.8)
                .build())
            .hourlyCounts(Map.of(0, 5L, 12, 120L))
            .workflowStats(WorkflowDashboardSummaryResponse.WorkflowStatsSummary.builder()
                .total(12)
                .active(8)
                .inactive(3)
                .errored(1)
                .running(2)
                .build())
            .build();

        given(dashboardService.getDashboardSummary(eq(userId))).willReturn(response);

        // when & then
        mockMvc.perform(get("/api/v1/workflows/dashboard/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.metrics.todayRuns").value(1240))
            .andExpect(jsonPath("$.data.metrics.percentageChange").value(12.0))
            .andExpect(jsonPath("$.data.metrics.averageDurationSeconds").value(1.6))
            .andExpect(jsonPath("$.data.metrics.successRate").value(97.8))
            .andExpect(jsonPath("$.data.workflowStats.active").value(8))
            .andExpect(jsonPath("$.data.workflowStats.errored").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/workflows/dashboard/executions — 최근 실행 로그 페이징 조회 성공")
    void getRecentExecutions_success() throws Exception {
        // given
        UUID execId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        
        WorkflowDashboardExecutionResponse runLog = WorkflowDashboardExecutionResponse.builder()
            .id(execId)
            .workflowId(workflowId)
            .workflowName("Slack 주간 리포트 자동화")
            .status(ExecutionStatus.SUCCESS)
            .durationSeconds(1.2)
            .triggerType(TriggerType.SCHEDULE)
            .startedAt(LocalDateTime.now())
            .build();

        PageResponse<WorkflowDashboardExecutionResponse> pageResponse = PageResponse.of(
            List.of(runLog), false, null
        );

        given(dashboardService.getRecentExecutions(eq(userId), anyString(), anyInt()))
            .willReturn(pageResponse);

        // when & then
        mockMvc.perform(get("/api/v1/workflows/dashboard/executions")
                .param("cursor", "0")
                .param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].workflowName").value("Slack 주간 리포트 자동화"))
            .andExpect(jsonPath("$.data.content[0].status").value("SUCCESS"));
    }

    @Test
    @DisplayName("GET /api/v1/workflows/dashboard/errors — 최근 오류 목록 페이징 조회 성공")
    void getRecentErrors_success() throws Exception {
        // given
        UUID execId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();

        WorkflowDashboardErrorResponse errorLog = WorkflowDashboardErrorResponse.builder()
            .executionId(execId)
            .workflowId(workflowId)
            .workflowName("신규 리드 CRM 등록")
            .failedNodeId("node-crm-post")
            .failedNodeType(NodeType.HTTP)
            .errorMessage("API rate limit exceeded")
            .startedAt(LocalDateTime.now())
            .build();

        PageResponse<WorkflowDashboardErrorResponse> pageResponse = PageResponse.of(
            List.of(errorLog), false, null
        );

        given(dashboardService.getRecentErrors(eq(userId), anyString(), anyInt()))
            .willReturn(pageResponse);

        // when & then
        mockMvc.perform(get("/api/v1/workflows/dashboard/errors")
                .param("cursor", "0")
                .param("size", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].workflowName").value("신규 리드 CRM 등록"))
            .andExpect(jsonPath("$.data.content[0].errorMessage").value("API rate limit exceeded"));
    }
}
