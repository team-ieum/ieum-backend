package com.ieum.api.workflow.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
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
import com.ieum.api.workflow.service.WorkflowValidationService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.engine.validation.WorkflowValidationException;
import com.ieum.workflowcore.engine.validation.WorkflowValidationResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 정적 검증의 HTTP 계약 — 검증 엔드포인트 응답 모양, 수동 실행 400 + data.errors, 사전 검사 순서.
 * 인증(401)은 SecurityConfig 몫이라 standalone MockMvc로는 보지 않는다 — dev e2e에서 확인한다.
 */
class WorkflowValidationEndpointTest {

    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final WorkflowValidationService validationService = mock(WorkflowValidationService.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final WorkflowValidationResult.ValidationError required =
        new WorkflowValidationResult.ValidationError("a", "config.mappings", "REQUIRED", "'매핑'을 입력하세요.");
    private final WorkflowValidationResult.ValidationError orphan =
        new WorkflowValidationResult.ValidationError(null, null, "TRIGGER_COUNT", "트리거는 하나여야 합니다.");
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(new WorkflowController(workflowService, mock(ExecutionRetryService.class),
                mock(ExecutionApprovalService.class), validationService))
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
    @DisplayName("POST /{id}/validate → 200, data={valid, errors[{nodeId, field, code, message}]} — null 키도 실린다")
    void validate_returnsResult() throws Exception {
        given(validationService.validate(userId, workflowId))
            .willReturn(new WorkflowValidationResult(false, List.of(required, orphan)));

        mockMvc.perform(post("/api/v1/workflows/{id}/validate", workflowId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.valid").value(false))
            .andExpect(jsonPath("$.data.errors[0].nodeId").value("a"))
            .andExpect(jsonPath("$.data.errors[0].field").value("config.mappings"))
            .andExpect(jsonPath("$.data.errors[0].code").value("REQUIRED"))
            .andExpect(jsonPath("$.data.errors[0].message").value("'매핑'을 입력하세요."))
            .andExpect(jsonPath("$.data.errors[1].nodeId").value(nullValue()))
            .andExpect(jsonPath("$.data.errors[1].field").value(nullValue()))
            .andExpect(jsonPath("$.data.hasErrors").doesNotExist());
    }

    @Test
    @DisplayName("POST /{id}/validate — agent 장애면 502 PROVIDER_ERROR")
    void validate_agentDown_502() throws Exception {
        given(validationService.validate(userId, workflowId))
            .willThrow(new CustomException(ErrorCode.PROVIDER_ERROR, "검증 서버에 연결할 수 없습니다."));

        mockMvc.perform(post("/api/v1/workflows/{id}/validate", workflowId))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("PROVIDER_ERROR"));
    }

    @Test
    @DisplayName("POST /{id}/execute — 검증 에러면 400 WORKFLOW_VALIDATION_FAILED + data.errors, 실행 기록을 만들지 않는다")
    void execute_validationErrors_400() throws Exception {
        willThrow(new WorkflowValidationException(List.of(required, orphan)))
            .given(validationService).assertExecutable(userId, workflowId);

        mockMvc.perform(post("/api/v1/workflows/{id}/execute", workflowId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("WORKFLOW_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("검증 실패 2건: '매핑'을 입력하세요."))
            .andExpect(jsonPath("$.data.errors.length()").value(2))
            .andExpect(jsonPath("$.data.errors[0].code").value("REQUIRED"))
            .andExpect(jsonPath("$.data.errors[1].nodeId").value(nullValue()));

        verify(workflowService, never()).executeWorkflow(any(), any(), any());
    }

    @Test
    @DisplayName("POST /{id}/execute — 통과(또는 agent 장애로 통과)면 사전 검사 → 실행 순서로 202")
    void execute_passes_thenRuns() throws Exception {
        given(workflowService.executeWorkflow(eq(userId), eq(workflowId), any()))
            .willReturn(WorkflowExecutionResponse.builder()
                .id(UUID.randomUUID()).status(ExecutionStatus.PENDING).build());

        mockMvc.perform(post("/api/v1/workflows/{id}/execute", workflowId))
            .andExpect(status().isAccepted());

        InOrder order = inOrder(validationService, workflowService);
        order.verify(validationService).assertExecutable(userId, workflowId);
        order.verify(workflowService).executeWorkflow(eq(userId), eq(workflowId), any());
    }
}
