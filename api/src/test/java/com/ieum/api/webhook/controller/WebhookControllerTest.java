package com.ieum.api.webhook.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.api.webhook.dto.WebhookTriggerRequest;
import com.ieum.api.webhook.service.WebhookService;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.time.LocalDateTime;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class WebhookControllerTest {

    @Mock
    private WebhookService webhookService;

    @InjectMocks
    private WebhookController webhookController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(webhookController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("WEBHOOK 워크플로우 트리거 성공 — 202 Accepted")
    void trigger_성공_202() throws Exception {
        UUID workflowId = UUID.randomUUID();
        WorkflowExecutionResponse response = WorkflowExecutionResponse.builder()
            .id(UUID.randomUUID())
            .workflowId(workflowId)
            .status(ExecutionStatus.PENDING)
            .triggerType(TriggerType.WEBHOOK)
            .startedAt(LocalDateTime.now())
            .build();

        given(webhookService.trigger(eq(workflowId), any(WebhookTriggerRequest.class)))
            .willReturn(response);

        mockMvc.perform(post("/webhooks/{workflowId}", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("payload", Map.of("orderId", "ORD-001", "amount", 75000)))))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.triggerType").value("WEBHOOK"));
    }

    @Test
    @DisplayName("payload 없이 호출 — 202 Accepted (빈 payload 허용)")
    void trigger_빈_payload_성공() throws Exception {
        UUID workflowId = UUID.randomUUID();
        WorkflowExecutionResponse response = WorkflowExecutionResponse.builder()
            .id(UUID.randomUUID())
            .workflowId(workflowId)
            .status(ExecutionStatus.PENDING)
            .triggerType(TriggerType.WEBHOOK)
            .startedAt(LocalDateTime.now())
            .build();

        given(webhookService.trigger(eq(workflowId), any())).willReturn(response);

        mockMvc.perform(post("/webhooks/{workflowId}", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("WEBHOOK 타입이 아닌 워크플로우 — 400 WEBHOOK_TRIGGER_MISMATCH")
    void trigger_WEBHOOK_타입_아님_400() throws Exception {
        UUID workflowId = UUID.randomUUID();
        willThrow(new CustomException(ErrorCode.WEBHOOK_TRIGGER_MISMATCH))
            .given(webhookService).trigger(eq(workflowId), any());

        mockMvc.perform(post("/webhooks/{workflowId}", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("비활성 워크플로우 — 400 WORKFLOW_NOT_ACTIVE")
    void trigger_비활성_워크플로우_400() throws Exception {
        UUID workflowId = UUID.randomUUID();
        willThrow(new CustomException(ErrorCode.WORKFLOW_NOT_ACTIVE))
            .given(webhookService).trigger(eq(workflowId), any());

        mockMvc.perform(post("/webhooks/{workflowId}", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("존재하지 않는 workflowId — 404 WORKFLOW_NOT_FOUND")
    void trigger_존재하지_않는_워크플로우_404() throws Exception {
        UUID workflowId = UUID.randomUUID();
        willThrow(new CustomException(ErrorCode.WORKFLOW_NOT_FOUND))
            .given(webhookService).trigger(eq(workflowId), any());

        mockMvc.perform(post("/webhooks/{workflowId}", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false));
    }
}
