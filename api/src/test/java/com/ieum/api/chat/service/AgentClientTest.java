package com.ieum.api.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.chat.dto.ChatAgentResponse;
import com.ieum.api.chat.service.AgentClient.AgentChatCallParams;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.io.IOException;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentClientTest {

    private MockWebServer mockWebServer;
    private AgentClient agentClient;
    private final UUID userId = UUID.randomUUID();

    private static final String CHAT_RESPONSE_BODY =
        "{\"message\":\"안녕하세요\",\"type\":\"CLARIFICATION_NEEDED\"}";

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        agentClient = new AgentClient(mockWebServer.url("/").toString(), 30, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    private AgentChatCallParams.AgentChatCallParamsBuilder baseParams() {
        return AgentChatCallParams.builder()
            .workflowId(UUID.randomUUID())
            .prompt("요약해줘")
            .llmProvider("CLAUDE")
            .userId(userId);
    }

    @Test
    @DisplayName("chat — useBetaPlatformKey=true면 X-Key-Mode:platform, X-LLM-Provider:GEMINI, 키 헤더 없음")
    void chat_betaPlatformKey_setsHeadersAndOmitsKey() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(CHAT_RESPONSE_BODY));

        ChatAgentResponse response = agentClient.chat(baseParams()
            .userRole("ROLE_USER")
            .useBetaPlatformKey(true)
            .build());

        assertThat(response.getContent()).isEqualTo("안녕하세요");
        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Key-Mode")).isEqualTo("platform");
        assertThat(recorded.getHeader("X-LLM-Provider")).isEqualTo("GEMINI");
        assertThat(recorded.getHeader("X-LLM-Api-Key")).isNull();
        assertThat(recorded.getHeader("X-User-Role")).isEqualTo("ROLE_USER");
    }

    @Test
    @DisplayName("chat — useBetaPlatformKey=false(BYOK)면 기존 키 헤더 그대로 유지, X-Key-Mode 없음")
    void chat_byok_keepsExistingHeaders() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(CHAT_RESPONSE_BODY));

        agentClient.chat(baseParams()
            .apiKey("decrypted-api-key")
            .useBetaPlatformKey(false)
            .build());

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Key-Mode")).isNull();
        assertThat(recorded.getHeader("X-LLM-Provider")).isEqualTo("CLAUDE");
        assertThat(recorded.getHeader("X-LLM-Api-Key")).isEqualTo("decrypted-api-key");
    }

    @Test
    @DisplayName("chatStream — useBetaPlatformKey=true면 X-Key-Mode:platform, X-LLM-Provider:GEMINI, 키 헤더 없음")
    void chatStream_betaPlatformKey_setsHeadersAndOmitsKey() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("event: done\ndata: " + CHAT_RESPONSE_BODY + "\n\n"));

        agentClient.chatStream(baseParams()
            .userRole("ROLE_USER")
            .useBetaPlatformKey(true)
            .build()
        ).blockLast();

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Key-Mode")).isEqualTo("platform");
        assertThat(recorded.getHeader("X-LLM-Provider")).isEqualTo("GEMINI");
        assertThat(recorded.getHeader("X-LLM-Api-Key")).isNull();
    }

    @Test
    @DisplayName("getToolSchema — agent GET /v1/tools/schema 응답 JSON을 그대로 돌려준다")
    void getToolSchema_returnsAgentBodyAsIs() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"tools\":[{\"name\":\"builtin:google_sheets_append\",\"fields\":"
                + "[{\"name\":\"spreadsheet_id\",\"optionsSource\":\"google.spreadsheets\"}]}]}"));

        JsonNode schema = agentClient.getToolSchema();

        assertThat(schema.path("tools").get(0).path("fields").get(0).path("optionsSource").asText())
            .isEqualTo("google.spreadsheets");
        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("GET");
        assertThat(recorded.getPath()).endsWith("/v1/tools/schema");
    }

    @Test
    @DisplayName("getToolSchema — agent 5xx는 기존 chat과 같은 매핑(PROVIDER_ERROR)")
    void getToolSchema_agentErrorMapsLikeChat() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> agentClient.getToolSchema())
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.PROVIDER_ERROR);
    }

    @Test
    @DisplayName("getToolSchema — agent 429는 PROVIDER_RATE_LIMITED (chat과 공유하는 매핑)")
    void getToolSchema_agent429MapsToRateLimited() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(429));

        assertThatThrownBy(() -> agentClient.getToolSchema())
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.PROVIDER_RATE_LIMITED);
    }
}
