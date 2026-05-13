package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionContext;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentNodeExecutorTest {

    private MockWebServer mockWebServer;
    private AgentNodeExecutor executor;
    private CredentialProvider credentialProvider;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        credentialProvider = mock(CredentialProvider.class);
        when(credentialProvider.getDecryptedApiKey(any())).thenReturn("decrypted-api-key");
        executor = new AgentNodeExecutor(mockWebServer.url("/").toString(), credentialProvider, 30);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    private Node buildAgentNode(String prompt, String llmProvider, String credentialId) {
        Map<String, Object> config = Map.of(
            "llmProvider", llmProvider,
            "credentialId", credentialId,
            "prompt", prompt
        );
        return new Node("node-1", NodeType.AI, "AI 노드", config);
    }

    private ExecutionCursor buildCursor() {
        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setAllNodes(Collections.emptyList());
        cursor.setAllEdges(Collections.emptyList());
        cursor.setContext(new ExecutionContext());
        return cursor;
    }

    @Test
    void execute_success_returnsSuccessResult() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"success\":true,\"output\":\"요약 완료\",\"metadata\":null,\"errorMessage\":null}"));

        Node node = buildAgentNode("다음 텍스트를 요약해주세요.", "CLAUDE", "cred-id-1");
        ExecutorResult result = executor.execute(node, Collections.emptyMap(), buildCursor());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).containsKey("output");
        assertThat(result.getOutput().get("output")).isEqualTo("요약 완료");
        assertThat(result.getErrorMessage()).isNull();

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/execute");
        assertThat(recorded.getHeader("X-LLM-Provider")).isEqualTo("CLAUDE");
        assertThat(recorded.getHeader("X-LLM-Api-Key")).isEqualTo("decrypted-api-key");
    }

    @Test
    void execute_serverError_returnsFailureResult() {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(500)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"error\":\"Internal Server Error\"}"));

        Node node = buildAgentNode("테스트 프롬프트", "OPENAI", "cred-id-2");
        ExecutorResult result = executor.execute(node, Collections.emptyMap(), buildCursor());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotBlank();
    }
}
