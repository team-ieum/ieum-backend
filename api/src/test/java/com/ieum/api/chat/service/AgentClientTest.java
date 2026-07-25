package com.ieum.api.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.chat.dto.ChatAgentResponse;
import com.ieum.api.chat.service.AgentClient.AgentChatCallParams;
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
}
