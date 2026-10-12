package com.ieum.api.webhook.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.api.webhook.service.HookPayloadReader;
import com.ieum.api.webhook.service.HookRequest;
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
    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    private final WebhookService webhookService = mock(WebhookService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new HookReceiveController(new HookPayloadReader(new ObjectMapper()), webhookService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        given(webhookService.resolveHook(TOKEN)).willReturn(WORKFLOW_ID);
    }

    private HookRequest receivedRequest() {
        ArgumentCaptor<HookRequest> captor = ArgumentCaptor.forClass(HookRequest.class);
        verify(webhookService).receive(eq(WORKFLOW_ID), captor.capture());
        return captor.getValue();
    }

    /** includeRaw false 모양(R4). */
    private Map<String, Object> received() {
        return receivedRequest().output(false);
    }

    @Test
    @DisplayName("감싸지 않은 JSON 객체(GitHub ping) → 그대로 payload, 200 {ok, executionId}")
    void unwrappedJsonObject() throws Exception {
        UUID executionId = UUID.randomUUID();
        given(webhookService.receive(eq(WORKFLOW_ID), any()))
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
        verify(webhookService, never()).receive(any(), any());
    }

    @Test
    @DisplayName("5MB 초과 → 413 WEBHOOK_PAYLOAD_TOO_LARGE, 정확히 5MB는 통과")
    void tooLarge_413() throws Exception {
        byte[] over = new byte[5 * 1024 * 1024 + 1];
        java.util.Arrays.fill(over, (byte) 'a');
        mockMvc.perform(post("/api/v1/hooks/{token}", TOKEN).contentType(MediaType.TEXT_PLAIN).content(over))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value(ErrorCode.WEBHOOK_PAYLOAD_TOO_LARGE.name()));
        verify(webhookService, never()).receive(any(), any());

        byte[] exact = new byte[5 * 1024 * 1024];
        java.util.Arrays.fill(exact, (byte) 'a');
        mockMvc.perform(post("/api/v1/hooks/{token}", TOKEN).contentType(MediaType.TEXT_PLAIN).content(exact))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("토큰이 없거나 틀리면 404 WORKFLOW_NOT_FOUND — 본문은 읽지 않는다(5MB 초과·깨진 JSON이어도 404)")
    void unknownToken_404_beforeBody() throws Exception {
        given(webhookService.resolveHook("nope")).willThrow(new CustomException(ErrorCode.WORKFLOW_NOT_FOUND));
        byte[] over = new byte[5 * 1024 * 1024 + 1];

        mockMvc.perform(post("/api/v1/hooks/{token}", "nope").contentType(MediaType.TEXT_PLAIN).content(over))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(ErrorCode.WORKFLOW_NOT_FOUND.name()));
        mockMvc.perform(post("/api/v1/hooks/{token}", "nope").contentType(MediaType.APPLICATION_JSON).content("{\"a\": "))
            .andExpect(status().isNotFound());
        verify(webhookService, never()).receive(any(), any());
    }

    @Test
    @DisplayName("분당 한도 초과 → 429 WEBHOOK_RATE_LIMITED — 본문을 읽기 전이라 깨진 JSON도 429")
    void rateLimited_429_beforeBody() throws Exception {
        given(webhookService.resolveHook(TOKEN)).willThrow(new CustomException(ErrorCode.WEBHOOK_RATE_LIMITED));

        mockMvc.perform(post("/api/v1/hooks/{token}", TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"a\": "))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value(ErrorCode.WEBHOOK_RATE_LIMITED.name()));
        verify(webhookService, never()).receive(any(), any());
    }

    @Test
    @DisplayName("includeRaw true → method·headers·query·body·rawBody, 자격증명 헤더는 대소문자 무관 가림, 서명 헤더는 원문")
    void includeRaw_shape() throws Exception {
        String raw = "{\"event\": \"fromBody\"}";
        mockMvc.perform(post("/api/v1/hooks/{token}?event=fromQuery&source=q", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .header("AUTHORIZATION", "Bearer secret")
                .header("Cookie", "sid=secret")
                .header("X-Hub-Signature-256", "sha256=abc")
                .header("Proxy-Authorization", "Basic secret")
                .header("Set-Cookie", "sid=secret")
                .header("X-API-Key", "secret")
                .header("X-Auth-Token", "secret")
                .header("X-Access-Token", "secret")
                .header("X-CSRF-Token", "secret")
                .header("X-XSRF-TOKEN", "secret")
                .header("X-Hub-Signature", "sha1=def")
                .header("Stripe-Signature", "t=1,v1=ghi")
                .header("X-Slack-Signature", "v0=jkl")
                .header("X-Gitlab-Token", "glverify")
                .header("X-Hook-Secret", "asanaverify")
                .content(raw))
            .andExpect(status().isOk());

        Map<String, Object> out = receivedRequest().output(true);
        assertThat(out).containsOnlyKeys("method", "headers", "query", "body", "rawBody")
            .containsEntry("method", "POST")
            .containsEntry("query", Map.of("event", "fromQuery", "source", "q"))
            .containsEntry("body", Map.of("event", "fromBody", "source", "q"))
            .containsEntry("rawBody", raw);
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) out.get("headers");
        assertThat(headers).containsEntry("authorization", "***")
            .containsEntry("cookie", "***")
            .containsEntry("proxy-authorization", "***")
            .containsEntry("set-cookie", "***")
            .containsEntry("x-api-key", "***")
            .containsEntry("x-auth-token", "***")
            .containsEntry("x-access-token", "***")
            .containsEntry("x-csrf-token", "***")
            .containsEntry("x-xsrf-token", "***")
            // 서명·검증 헤더는 includeRaw의 존재 이유다 — 가리면 워크플로우가 서명을 검증할 수 없다
            .containsEntry("x-hub-signature-256", "sha256=abc")
            .containsEntry("x-hub-signature", "sha1=def")
            .containsEntry("stripe-signature", "t=1,v1=ghi")
            .containsEntry("x-slack-signature", "v0=jkl")
            .containsEntry("x-gitlab-token", "glverify")
            .containsEntry("x-hook-secret", "asanaverify")
            .doesNotContainKeys("AUTHORIZATION", "Cookie");
        assertThat(headers.values().toString()).doesNotContain("secret");
    }

    @Test
    @DisplayName("includeRaw false → Task 2 모양(R4) 그대로, 헤더·원문 없음")
    void includeRawFalse_unchanged() throws Exception {
        mockMvc.perform(post("/api/v1/hooks/{token}", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer secret")
                .content("[1]"))
            .andExpect(status().isOk());
        assertThat(received()).isEqualTo(Map.of("items", List.of(1)));
    }

    @Test
    @DisplayName("includeRaw GET → rawBody 빈 문자열, body는 쿼리")
    void includeRaw_get() throws Exception {
        mockMvc.perform(get("/api/v1/hooks/{token}?a=1", TOKEN)).andExpect(status().isOk());
        Map<String, Object> out = receivedRequest().output(true);
        assertThat(out).containsEntry("method", "GET").containsEntry("rawBody", "")
            .containsEntry("body", Map.of("a", "1")).containsEntry("query", Map.of("a", "1"));
    }
}
