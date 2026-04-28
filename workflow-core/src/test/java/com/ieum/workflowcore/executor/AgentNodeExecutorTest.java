package com.ieum.workflowcore.executor;

import com.ieum.workflowcore.executor.dto.AgentExecutionResult;
import com.ieum.workflowcore.executor.dto.AgentNodeRequest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class AgentNodeExecutorTest {

    private MockWebServer mockWebServer;
    private AgentNodeExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        executor = new AgentNodeExecutor(mockWebServer.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void execute_success_returnsSuccessResult() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"output\":\"요약 완료\",\"metadata\":null,\"errorMessage\":null}"));

        AgentNodeRequest request = AgentNodeRequest.builder()
                .nodeId("node-1")
                .renderedPrompt("다음 텍스트를 요약해주세요.")
                .build();

        AgentExecutionResult result = executor.execute(request, "CLAUDE", "sk-ant-test-key");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("요약 완료");
        assertThat(result.getErrorMessage()).isNull();

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/execute");
        assertThat(recorded.getHeader("X-LLM-Provider")).isEqualTo("CLAUDE");
        assertThat(recorded.getHeader("X-LLM-Api-Key")).isEqualTo("sk-ant-test-key");
    }

    @Test
    void execute_serverError_returnsFailureResult() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Internal Server Error\"}"));

        AgentNodeRequest request = AgentNodeRequest.builder()
                .nodeId("node-2")
                .renderedPrompt("테스트 프롬프트")
                .build();

        AgentExecutionResult result = executor.execute(request, "OPENAI", "sk-test-key");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotBlank();
    }
}
