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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentNodeExecutorTest {

    private MockWebServer mockWebServer;
    private AgentNodeExecutor executor;
    private CredentialProvider credentialProvider;
    private GoogleTokenProvider googleTokenProvider;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        credentialProvider = mock(CredentialProvider.class);
        googleTokenProvider = mock(GoogleTokenProvider.class);
        when(credentialProvider.getDecryptedApiKey(any())).thenReturn("decrypted-api-key");
        executor = new AgentNodeExecutor(
            mockWebServer.url("/").toString(),
            credentialProvider,
            googleTokenProvider,
            30
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private Node buildAgentNode(String prompt, String llmProvider, String credentialId) {
        Map<String, Object> config = Map.of(
            "llmProvider", llmProvider,
            "credentialId", credentialId,
            "prompt", prompt
        );
        return new Node("node-1", NodeType.AI, "AI 노드", config);
    }

    private Node buildAgentNodeWithTools(
        String prompt, String llmProvider, String credentialId,
        List<Map<String, Object>> tools
    ) {
        Map<String, Object> config = new java.util.HashMap<>();
        config.put("llmProvider", llmProvider);
        config.put("credentialId", credentialId);
        config.put("prompt", prompt);
        config.put("tools", tools);
        return new Node("node-1", NodeType.AI, "AI 노드", config);
    }

    private ExecutionCursor buildCursor() {
        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setAllNodes(Collections.emptyList());
        cursor.setAllEdges(Collections.emptyList());
        cursor.setContext(new ExecutionContext());
        return cursor;
    }

    private ExecutionCursor buildCursorWithUserId(UUID userId) {
        ExecutionContext context = new ExecutionContext();
        context.setUserId(userId);
        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setAllNodes(Collections.emptyList());
        cursor.setAllEdges(Collections.emptyList());
        cursor.setContext(context);
        return cursor;
    }

    private static final String SUCCESS_RESPONSE =
        "{\"success\":true,\"output\":\"완료\",\"metadata\":null,\"errorMessage\":null}";

    // ── 기존 케이스 ──────────────────────────────────────────────────────────

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

    // ── Google 빌트인 도구 케이스 ─────────────────────────────────────────────

    @Test
    void execute_withGoogleBuiltinTool_injectsGoogleAccessTokenHeader() throws InterruptedException {
        // given
        UUID userId = UUID.randomUUID();
        String googleToken = "ya29.google-access-token";
        when(googleTokenProvider.getValidAccessToken(userId)).thenReturn(googleToken);

        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        Node node = buildAgentNodeWithTools("캘린더 확인해줘", "CLAUDE", "cred-id",
            List.of(Map.of("name", "builtin:google_calendar")));
        ExecutionCursor cursor = buildCursorWithUserId(userId);

        // when
        ExecutorResult result = executor.execute(node, Collections.emptyMap(), cursor);

        // then
        assertThat(result.isSuccess()).isTrue();
        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Google-Access-Token")).isEqualTo(googleToken);
    }

    @Test
    void execute_withoutGoogleTool_noGoogleAccessTokenHeader() throws InterruptedException {
        // given — 일반 도구 (builtin:google_* 아님)
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        Node node = buildAgentNodeWithTools("웹 검색해줘", "CLAUDE", "cred-id",
            List.of(Map.of("name", "web_search")));
        ExecutionCursor cursor = buildCursor();

        // when
        executor.execute(node, Collections.emptyMap(), cursor);

        // then — X-Google-Access-Token 헤더 없음, googleTokenProvider 호출 없음
        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Google-Access-Token")).isNull();
        verify(googleTokenProvider, never()).getValidAccessToken(any());
    }

    @Test
    void execute_withGoogleToolButNoUserId_noGoogleAccessTokenHeader() throws InterruptedException {
        // given — Google 도구가 있지만 userId가 없는 커서 (userId=null)
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        Node node = buildAgentNodeWithTools("Gmail 확인해줘", "CLAUDE", "cred-id",
            List.of(Map.of("name", "builtin:google_gmail")));
        ExecutionCursor cursor = buildCursor(); // userId 없음

        // when
        executor.execute(node, Collections.emptyMap(), cursor);

        // then — userId 없으면 토큰 조회 없이 헤더 미포함으로 진행
        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Google-Access-Token")).isNull();
        verify(googleTokenProvider, never()).getValidAccessToken(any());
    }
}
