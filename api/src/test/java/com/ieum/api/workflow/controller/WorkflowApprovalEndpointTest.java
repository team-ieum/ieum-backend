package com.ieum.api.workflow.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.api.workflow.service.ExecutionApprovalService;
import com.ieum.api.workflow.service.ExecutionRetryService;
import com.ieum.api.workflow.service.WorkflowService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 승인·거부 엔드포인트의 HTTP 계약(상태 코드·본문 검증·로그인 사용자 전달).
 * 인증 자체(토큰 없으면 401)는 SecurityConfig의 {@code anyRequest().authenticated()} 몫이라
 * standalone MockMvc로는 볼 수 없다 — 머지 전 수동 e2e에서 확인한다.
 */
class WorkflowApprovalEndpointTest {

    private final ExecutionApprovalService approvalService = mock(ExecutionApprovalService.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(new WorkflowController(
                mock(WorkflowService.class), mock(ExecutionRetryService.class), approvalService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();
        CustomUserDetails principal = CustomUserDetails.of(userId, "user@example.com", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST .../approve → 202, 로그인 사용자로 승인하고 새 실행을 돌려준다")
    void approve_returns202WithContinuation() throws Exception {
        UUID continuationId = UUID.randomUUID();
        given(approvalService.approve(userId, executionId)).willReturn(WorkflowExecutionResponse.builder()
            .id(continuationId).status(ExecutionStatus.PENDING).build());

        mockMvc.perform(post("/api/v1/workflows/executions/{executionId}/approve", executionId))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(continuationId.toString()));
    }

    @Test
    @DisplayName("POST .../reject + 사유 → 200, 사유가 서비스로 전달된다")
    void reject_withReason_returns200() throws Exception {
        given(approvalService.reject(userId, executionId, "금액 재확인 필요")).willReturn(
            WorkflowExecutionResponse.builder().id(executionId).status(ExecutionStatus.FAILED).build());

        mockMvc.perform(post("/api/v1/workflows/executions/{executionId}/reject", executionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"금액 재확인 필요\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("FAILED"));
    }

    @Test
    @DisplayName("POST .../reject 본문 없음 → 200, 사유 null")
    void reject_withoutBody_passesNullReason() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/executions/{executionId}/reject", executionId))
            .andExpect(status().isOk());

        verify(approvalService).reject(eq(userId), eq(executionId), isNull());
    }

    @Test
    @DisplayName("거부 사유가 200자를 넘으면 400 — node_runs.error_message(VARCHAR 255)에서 500으로 터지지 않게 경계에서 막는다")
    void reject_tooLongReason_returns400() throws Exception {
        String reason = "가".repeat(201);

        mockMvc.perform(post("/api/v1/workflows/executions/{executionId}/reject", executionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}"))
            .andExpect(status().isBadRequest());

        verify(approvalService, never()).reject(any(), any(), any());
    }

    @Test
    @DisplayName("대기 상태가 아닌 실행 승인 → 409")
    void approve_notWaiting_returns409() throws Exception {
        given(approvalService.approve(userId, executionId)).willThrow(
            new CustomException(ErrorCode.EXECUTION_NOT_WAITING_APPROVAL, "현재 상태: SUCCESS"));

        mockMvc.perform(post("/api/v1/workflows/executions/{executionId}/approve", executionId))
            .andExpect(status().isConflict());
    }
}
