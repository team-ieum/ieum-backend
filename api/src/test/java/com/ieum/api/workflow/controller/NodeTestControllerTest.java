package com.ieum.api.workflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.api.workflow.dto.NodeTestRequest;
import com.ieum.api.workflow.dto.NodeTestResponse;
import com.ieum.api.workflow.service.NodeTestService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.enums.SampleStatus;
import com.ieum.workflowcore.service.NodeTestResult;
import com.ieum.workflowcore.service.TestSampleMissingException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 노드 테스트 엔드포인트의 HTTP 계약. 인증 자체(토큰 없으면 401)는 SecurityConfig 몫이라 수동 e2e로 본다. */
class NodeTestControllerTest {

    private final NodeTestService service = mock(NodeTestService.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new NodeTestController(service))
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

    private static NodeTestResponse success() {
        return NodeTestResponse.from(new NodeTestResult(
            SampleStatus.SUCCESS, Map.of("ok", true), null, LocalDateTime.of(2026, 10, 6, 12, 0)));
    }

    @Test
    @DisplayName("[Review Focus 1] 소유자 검사에 쓰이는 id는 로그인 사용자 — 경로·body 값이 아니다")
    void loginUserIdIsUsedForOwnerCheck() throws Exception {
        given(service.test(eq(userId), eq(workflowId), eq("node-1"), any(NodeTestRequest.class)))
            .willReturn(success());

        mockMvc.perform(post("/api/v1/workflows/{workflowId}/nodes/{nodeId}/test", workflowId, "node-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"input\":{\"a\":1}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.output.ok").value(true))
            .andExpect(jsonPath("$.data.testedAt").exists())
            .andExpect(jsonPath("$.data.webhookUrl").doesNotExist());

        ArgumentCaptor<NodeTestRequest> request = ArgumentCaptor.forClass(NodeTestRequest.class);
        verify(service).test(eq(userId), eq(workflowId), eq("node-1"), request.capture());
        assertThat(request.getValue().getInput()).isEqualTo(Map.of("a", 1));
    }

    @Test
    @DisplayName("본문 없이도 호출된다 — 저장된 노드를 테스트")
    void emptyBodyIsAllowed() throws Exception {
        given(service.test(eq(userId), eq(workflowId), eq("node-1"), any(NodeTestRequest.class)))
            .willReturn(success());

        mockMvc.perform(post("/api/v1/workflows/{workflowId}/nodes/{nodeId}/test", workflowId, "node-1"))
            .andExpect(status().isOk());

        ArgumentCaptor<NodeTestRequest> request = ArgumentCaptor.forClass(NodeTestRequest.class);
        verify(service).test(eq(userId), eq(workflowId), eq("node-1"), request.capture());
        assertThat(request.getValue().getNode()).isNull();
        assertThat(request.getValue().getInput()).isNull();
    }

    @Test
    @DisplayName("body 노드에 label이 없으면 400 — 서비스 호출 없음")
    void invalidBodyNodeIs400() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/{workflowId}/nodes/{nodeId}/test", workflowId, "node-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"node\":{\"id\":\"node-1\",\"type\":\"HTTP\"}}"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("샘플 누락 → 400 TEST_SAMPLE_MISSING + data.missingNodeIds")
    void sampleMissingReturnsIds() throws Exception {
        given(service.test(any(), any(), any(), any()))
            .willThrow(new TestSampleMissingException(List.of("up1", "up2")));

        mockMvc.perform(post("/api/v1/workflows/{workflowId}/nodes/{nodeId}/test", workflowId, "node-1")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("TEST_SAMPLE_MISSING"))
            .andExpect(jsonPath("$.data.missingNodeIds[0]").value("up1"))
            .andExpect(jsonPath("$.data.missingNodeIds[1]").value("up2"));
    }

    @Test
    @DisplayName("webhook 대기 응답은 status·webhookUrl·expiresAt만 싣는다")
    void listeningResponseShape() throws Exception {
        given(service.test(any(), any(), any(), any())).willReturn(
            NodeTestResponse.listening(workflowId, LocalDateTime.of(2026, 10, 6, 12, 5)));

        mockMvc.perform(post("/api/v1/workflows/{workflowId}/nodes/{nodeId}/test", workflowId, "t")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("LISTENING"))
            .andExpect(jsonPath("$.data.webhookUrl").value("/webhooks/" + workflowId))
            .andExpect(jsonPath("$.data.expiresAt").exists())
            .andExpect(jsonPath("$.data.output").doesNotExist())
            .andExpect(jsonPath("$.data.testedAt").doesNotExist());
    }

    @Test
    @DisplayName("GET sample — 200 / 샘플 없으면 404 TEST_SAMPLE_NOT_FOUND")
    void getSampleOkAndNotFound() throws Exception {
        given(service.getSample(userId, workflowId, "node-1")).willReturn(success());
        given(service.getSample(userId, workflowId, "none"))
            .willThrow(new CustomException(ErrorCode.TEST_SAMPLE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/workflows/{workflowId}/nodes/{nodeId}/sample", workflowId, "node-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        mockMvc.perform(get("/api/v1/workflows/{workflowId}/nodes/{nodeId}/sample", workflowId, "none"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("TEST_SAMPLE_NOT_FOUND"));
    }
}
