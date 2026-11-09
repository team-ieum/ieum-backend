package com.ieum.api.workflow.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.api.workflow.dto.ApprovalGateDetailResponse;
import com.ieum.api.workflow.dto.ApprovalGateSummaryResponse;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.api.workflow.service.ApprovalGateService;
import com.ieum.api.workflow.service.ApprovalQueryService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.PageResponse;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

/** 승인 게이트 API의 HTTP 계약(로그인 사용자 전달·본문 검증·응답 모양). 서비스는 mock. */
class ApprovalControllerTest {

    private final ApprovalQueryService queryService = mock(ApprovalQueryService.class);
    private final ApprovalGateService gateService = mock(ApprovalGateService.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID gateId = UUID.randomUUID();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(new ApprovalController(queryService, gateService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();
        CustomUserDetails user = CustomUserDetails.of(userId, "user@example.com", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /approvals — 로그인 사용자의 대기 게이트 목록을 돌려준다")
    void 목록은_내_대기_게이트만() throws Exception {
        ApprovalGateSummaryResponse row = ApprovalGateSummaryResponse.builder()
            .gateId(gateId).executionId(UUID.randomUUID()).workflowId(UUID.randomUUID())
            .workflowName("결재").nodeId("g1").event("approval.request").title("확인해 주세요")
            .deadline(LocalDateTime.now().plusHours(1)).createdAt(LocalDateTime.now()).build();
        given(queryService.list(userId, "1", 20)).willReturn(PageResponse.of(List.of(row), true, "2"));

        mockMvc.perform(get("/api/v1/approvals").param("cursor", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].gateId").value(gateId.toString()))
            .andExpect(jsonPath("$.data.content[0].workflowName").value("결재"))
            .andExpect(jsonPath("$.data.content[0].title").value("확인해 주세요"))
            .andExpect(jsonPath("$.data.nextCursor").value("2"));
        verify(queryService).list(userId, "1", 20);
    }

    @Test
    @DisplayName("GET /approvals/{gateId} — 저장된 view를 그대로 싣는다")
    void 상세는_view를_그대로() throws Exception {
        ApprovalGateDetailResponse detail = ApprovalGateDetailResponse.builder()
            .gateId(gateId).nodeId("g1").event("approval.request")
            .view(Map.of("message", "확인해 주세요", "items", List.of(Map.of("key", "draft", "value", "초안"))))
            .decision("approved").build();
        given(queryService.detail(userId, gateId)).willReturn(detail);

        mockMvc.perform(get("/api/v1/approvals/{gateId}", gateId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.gateId").value(gateId.toString()))
            .andExpect(jsonPath("$.data.view.message").value("확인해 주세요"))
            .andExpect(jsonPath("$.data.view.items[0].value").value("초안"))
            .andExpect(jsonPath("$.data.decision").value("approved"));
    }

    @Test
    @DisplayName("note가 201자면 400이고 서비스를 부르지 않는다")
    void note_201자는_400() throws Exception {
        String body = "{\"note\":\"" + "가".repeat(201) + "\"}";

        mockMvc.perform(post("/api/v1/approvals/{gateId}/approve", gateId)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/approvals/{gateId}/reject", gateId)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());

        verify(gateService, never()).approve(any(), any(), any(), any());
        verify(gateService, never()).reject(any(), any(), any());
    }

    @Test
    @DisplayName("submit에 values가 없으면 400")
    void submit_values_없으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/approvals/{gateId}/submit", gateId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"메모\"}"))
            .andExpect(status().isBadRequest());

        verify(gateService, never()).submit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("처리 응답은 실행 응답이다 — 승인·거절·제출 모두 200")
    void 처리_응답은_실행() throws Exception {
        UUID executionId = UUID.randomUUID();
        WorkflowExecutionResponse response = WorkflowExecutionResponse.builder()
            .id(executionId).status(ExecutionStatus.WAITING_APPROVAL)
            .pendingApprovalNodeIds(List.of("g2")).build();
        given(gateService.approve(eq(userId), eq(gateId), eq("좋아요"), any())).willReturn(response);
        given(gateService.reject(userId, gateId, null)).willReturn(response);
        given(gateService.submit(eq(userId), eq(gateId), any(), isNull())).willReturn(response);

        mockMvc.perform(post("/api/v1/approvals/{gateId}/approve", gateId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"좋아요\",\"content\":{\"draft\":\"고침\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(executionId.toString()))
            .andExpect(jsonPath("$.data.status").value("WAITING_APPROVAL"))
            .andExpect(jsonPath("$.data.pendingApprovalNodeIds[0]").value("g2"));
        verify(gateService).approve(userId, gateId, "좋아요", Map.of("draft", "고침"));

        mockMvc.perform(post("/api/v1/approvals/{gateId}/reject", gateId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(executionId.toString()));

        mockMvc.perform(post("/api/v1/approvals/{gateId}/submit", gateId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"values\":{\"amount\":3}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(executionId.toString()));
        verify(gateService).submit(userId, gateId, Map.of("amount", 3), null);
    }
}
