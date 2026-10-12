package com.ieum.api.workflow.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowWebhookTokenService;
import com.ieum.workflowcore.service.WorkflowWebhookTokenService.IssuedToken;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 웹훅 토큰 발급·조회 HTTP 계약. 소유자 검사는 로그인 사용자 id로 {@code getWorkflowByOwner}를 지난다(R9). */
class WorkflowWebhookTokenControllerTest {

    private static final String PATH = "/api/v1/workflows/{workflowId}/webhook-token";

    private final WorkflowCrudService crudService = mock(WorkflowCrudService.class);
    private final WorkflowWebhookTokenService tokenService = mock(WorkflowWebhookTokenService.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new WorkflowWebhookTokenController(crudService, tokenService))
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
    @DisplayName("발급 — 평문 token과 수신 경로가 ApiResponse로 나간다")
    void issueReturnsPlainTokenOnce() throws Exception {
        Workflow workflow = mock(Workflow.class);
        given(crudService.getWorkflowByOwner(userId, workflowId)).willReturn(workflow);
        given(tokenService.issue(workflow)).willReturn(new IssuedToken("abc_-123", LocalDateTime.of(2026, 10, 12, 9, 0)));

        mockMvc.perform(post(PATH, workflowId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.token").value("abc_-123"))
            .andExpect(jsonPath("$.data.path").value("/api/v1/hooks/abc_-123"))
            .andExpect(jsonPath("$.data.issuedAt").exists());
    }

    @Test
    @DisplayName("남의 워크플로우 발급은 404 — 토큰 서비스에 닿지 않는다")
    void issueOnForeignWorkflowIs404() throws Exception {
        given(crudService.getWorkflowByOwner(userId, workflowId))
            .willThrow(new CustomException(ErrorCode.WORKFLOW_NOT_FOUND));

        mockMvc.perform(post(PATH, workflowId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(tokenService);
    }

    @Test
    @DisplayName("남의 워크플로우 조회도 404")
    void statusOnForeignWorkflowIs404() throws Exception {
        given(crudService.getWorkflowByOwner(userId, workflowId))
            .willThrow(new CustomException(ErrorCode.WORKFLOW_NOT_FOUND));

        mockMvc.perform(get(PATH, workflowId))
            .andExpect(status().isNotFound());

        verifyNoInteractions(tokenService);
    }

    @Test
    @DisplayName("조회 — issued·issuedAt만, 토큰 평문·해시는 없다")
    void statusHasNoTokenValue() throws Exception {
        given(tokenService.status(workflowId)).willReturn(Optional.of(LocalDateTime.of(2026, 10, 12, 9, 0)));

        mockMvc.perform(get(PATH, workflowId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.issued").value(true))
            .andExpect(jsonPath("$.data.issuedAt").exists())
            .andExpect(jsonPath("$.data.token").doesNotExist())
            .andExpect(jsonPath("$.data.tokenHash").doesNotExist())
            .andExpect(jsonPath("$.data.path").doesNotExist());
    }

    @Test
    @DisplayName("미발급 조회 — issued false, issuedAt null")
    void statusNotIssued() throws Exception {
        given(tokenService.status(workflowId)).willReturn(Optional.empty());

        mockMvc.perform(get(PATH, workflowId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.issued").value(false))
            .andExpect(jsonPath("$.data.issuedAt").doesNotExist());
    }
}
