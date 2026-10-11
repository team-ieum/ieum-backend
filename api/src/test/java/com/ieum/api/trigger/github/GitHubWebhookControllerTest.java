package com.ieum.api.trigger.github;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
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
class GitHubWebhookControllerTest {

    @Mock private GitHubWebhookService service;
    @InjectMocks private GitHubWebhookController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("헤더 4개와 raw 바이트를 그대로 서비스에 넘기고 202")
    void passesRawBytes() throws Exception {
        String body = "{\"action\":\"opened\"}";
        mockMvc.perform(post("/api/v1/integrations/github/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature-256", "sha256=abc")
                .header("X-GitHub-Event", "issues")
                .header("X-GitHub-Delivery", "d-1")
                .header("X-GitHub-Hook-Installation-Target-ID", "1001")
                .content(body))
            .andExpect(status().isAccepted());

        verify(service).receive("sha256=abc", "issues", "d-1", "1001", body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("서명 불일치는 401")
    void unauthorized() throws Exception {
        willThrow(new CustomException(ErrorCode.UNAUTHORIZED)).given(service).receive(any(), eq("issues"), any(), any(), any());
        mockMvc.perform(post("/api/v1/integrations/github/webhook")
                .contentType(MediaType.APPLICATION_JSON).header("X-GitHub-Event", "issues").content("{}"))
            .andExpect(status().isUnauthorized());
    }
}
