package com.ieum.api.webhook.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.api.webhook.service.HookPayloadReader;
import com.ieum.api.webhook.service.WebhookService;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 토큰 주소 원문 수신 — 실제 {@link HookPayloadReader}로 본문을 풀고, 서비스에 넘어간 payload를 본다. */
class HookReceiveControllerTest {

    private static final String TOKEN = "tok_abc";

    private final WebhookService webhookService = mock(WebhookService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new HookReceiveController(new HookPayloadReader(new ObjectMapper()), webhookService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> received() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(webhookService).receive(eq(TOKEN), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("감싸지 않은 JSON 객체(GitHub ping) → 그대로 payload, 200 {ok, executionId}")
    void unwrappedJsonObject() throws Exception {
        UUID executionId = UUID.randomUUID();
        given(webhookService.receive(eq(TOKEN), anyMap()))
            .willReturn(WorkflowExecutionResponse.builder().id(executionId).build());

        mockMvc.perform(post("/api/v1/hooks/{token}", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"zen\": \"Keep it logically awesome.\", \"hook_id\": 1, \"hook\": {\"type\": \"Repository\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.executionId").value(executionId.toString()))
            .andExpect(jsonPath("$.success").doesNotExist());

        assertThat(received()).containsEntry("zen", "Keep it logically awesome.")
            .containsEntry("hook_id", 1)
            .containsEntry("hook", Map.of("type", "Repository"))
            .doesNotContainKey("payload");
    }

    @Test
    @DisplayName("샘플만 저장(서비스 null) → 200, executionId null")
    void sampleOnly_executionIdNull() throws Exception {
        mockMvc.perform(post("/api/v1/hooks/{token}", TOKEN).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.executionId").isEmpty());
    }

    @Test
    @DisplayName("JSON 배열 → items")
    void arrayAndScalar() throws Exception {
        mockMvc.perform(post("/api/v1/hooks/{token}", TOKEN).contentType(MediaType.APPLICATION_JSON).content("[1, 2]"))
            .andExpect(status().isOk());
        assertThat(received()).isEqualTo(Map.of("items", List.of(1, 2)));
    }

    @Test
    @DisplayName("JSON 스칼라 → value")
    void scalar() throws Exception {
        mockMvc.perform(post("/api/v1/hooks/{token}", TOKEN).contentType(MediaType.APPLICATION_JSON).content("\"hi\""))
            .andExpect(status().isOk());
        assertThat(received()).isEqualTo(Map.of("value", "hi"));
    }

    @Test
    @DisplayName("Content-Type 없음 → JSON으로 읽는다")
    void noContentTypeIsJson() throws Exception {
        mockMvc.perform(post("/api/v1/hooks/{token}", TOKEN).content("{\"a\": 1}"))
            .andExpect(status().isOk());
        assertThat(received()).isEqualTo(Map.of("a", 1));
    }

    @Test
    @DisplayName("form 본문 → 키→값, 같은 키 여러 번이면 리스트, URL 디코딩")
    void form() throws Exception {
        mockMvc.perform(post("/api/v1/hooks/{token}", TOKEN)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("name=%ED%99%8D+%EA%B8%B8%EB%8F%99&tag=a&tag=b&empty="))
            .andExpect(status().isOk());
        assertThat(received()).isEqualTo(Map.of("name", "홍 길동", "tag", List.of("a", "b"), "empty", ""));
    }

    @Test
    @DisplayName("PUT도 받는다 — 그 밖의 Content-Type은 {value: 원문}")
    void putTextBody() throws Exception {
        mockMvc.perform(put("/api/v1/hooks/{token}", TOKEN).contentType(MediaType.TEXT_PLAIN).content("plain text"))
            .andExpect(status().isOk());
        assertThat(received()).isEqualTo(Map.of("value", "plain text"));
    }

    @Test
    @DisplayName("GET 쿼리 → 쿼리 맵")
    void getQuery() throws Exception {
        mockMvc.perform(get("/api/v1/hooks/{token}?event=ping&id=7&id=8", TOKEN))
            .andExpect(status().isOk());
        assertThat(received()).isEqualTo(Map.of("event", "ping", "id", List.of("7", "8")));
    }

    @Test
    @DisplayName("본문과 쿼리가 같이 오면 합치되 본문 키가 우선")
    void bodyWinsOverQuery() throws Exception {
        mockMvc.perform(post("/api/v1/hooks/{token}?event=fromQuery&source=q", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\": \"fromBody\"}"))
            .andExpect(status().isOk());
        assertThat(received()).isEqualTo(Map.of("event", "fromBody", "source", "q"));
    }

    @Test
    @DisplayName("본문 없는 POST → 쿼리만")
    void emptyBodyUsesQueryOnly() throws Exception {
        mockMvc.perform(post("/api/v1/hooks/{token}?a=1", TOKEN).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
        assertThat(received()).isEqualTo(Map.of("a", "1"));
    }

    @Test
    @DisplayName("깨진 JSON(뒤에 쓰레기 포함) → 400, 서비스 호출 없음")
    void brokenJson_400() throws Exception {
        for (String body : List.of("{\"a\": ", "{\"a\": 1} trailing")) {
            mockMvc.perform(post("/api/v1/hooks/{token}", TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()));
        }
        verifyNoInteractions(webhookService);
    }

    @Test
    @DisplayName("5MB 초과 → 413 WEBHOOK_PAYLOAD_TOO_LARGE, 정확히 5MB는 통과")
    void tooLarge_413() throws Exception {
        byte[] over = new byte[5 * 1024 * 1024 + 1];
        java.util.Arrays.fill(over, (byte) 'a');
        mockMvc.perform(post("/api/v1/hooks/{token}", TOKEN).contentType(MediaType.TEXT_PLAIN).content(over))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value(ErrorCode.WEBHOOK_PAYLOAD_TOO_LARGE.name()));
        verifyNoInteractions(webhookService);

        byte[] exact = new byte[5 * 1024 * 1024];
        java.util.Arrays.fill(exact, (byte) 'a');
        mockMvc.perform(post("/api/v1/hooks/{token}", TOKEN).contentType(MediaType.TEXT_PLAIN).content(exact))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("토큰이 없거나 틀리면 404 WORKFLOW_NOT_FOUND")
    void unknownToken_404() throws Exception {
        given(webhookService.receive(any(), anyMap())).willThrow(new CustomException(ErrorCode.WORKFLOW_NOT_FOUND));

        mockMvc.perform(post("/api/v1/hooks/{token}", "nope").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(ErrorCode.WORKFLOW_NOT_FOUND.name()));
    }
}
