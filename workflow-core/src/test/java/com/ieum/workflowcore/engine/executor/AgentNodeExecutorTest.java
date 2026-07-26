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
import org.junit.jupiter.api.DisplayName;
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
    private NotionTokenProvider notionTokenProvider;
    private GitHubTokenProvider gitHubTokenProvider;
    private GoogleTokenProvider googleTokenProvider;
    private BetaPlatformProvider betaPlatformProvider;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        credentialProvider = mock(CredentialProvider.class);
        notionTokenProvider = mock(NotionTokenProvider.class);
        gitHubTokenProvider = mock(GitHubTokenProvider.class);
        googleTokenProvider = mock(GoogleTokenProvider.class);
        betaPlatformProvider = new StubBetaPlatformProvider();
        when(credentialProvider.getDecryptedApiKey(any())).thenReturn("decrypted-api-key");
        executor = new AgentNodeExecutor(
            mockWebServer.url("/").toString(),
            credentialProvider,
            googleTokenProvider,
            new ToolAuthResolver(credentialProvider, notionTokenProvider, gitHubTokenProvider),
            new StubMcpCatalogProvider(),
            new StubWebhookCredentialProvider(),
            uid -> null,
            betaPlatformProvider,
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

    private Node buildAgentNodeNoCredential(String prompt, String llmProvider) {
        // Map.of()는 null 값을 허용하지 않으므로 credentialId 없는 케이스는 HashMap으로 구성한다.
        Map<String, Object> config = new java.util.HashMap<>();
        config.put("llmProvider", llmProvider);
        config.put("prompt", prompt);
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

    @Test
    @DisplayName("실행 traceId와 nodeId가 X-Trace-Id·X-Node-Id 헤더로 전송된다")
    void execute_sendsTraceHeaders() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        ExecutionCursor cursor = buildCursor();
        cursor.getContext().setTraceId("abcdef0123456789abcdef0123456789");

        executor.execute(buildAgentNode("요약해줘", "CLAUDE", "cred-id-1"),
            Collections.emptyMap(), cursor);

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Trace-Id")).isEqualTo("abcdef0123456789abcdef0123456789");
        assertThat(recorded.getHeader("X-Node-Id")).isEqualTo("node-1");
    }

    @Test
    @DisplayName("traceId가 없으면 X-Trace-Id 헤더를 보내지 않는다")
    void execute_noTraceId_omitsHeader() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        executor.execute(buildAgentNode("요약해줘", "CLAUDE", "cred-id-1"),
            Collections.emptyMap(), buildCursor());

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Trace-Id")).isNull();
        assertThat(recorded.getHeader("X-Node-Id")).isEqualTo("node-1");
    }

    // ── 기존 케이스 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("정상 실행 시 success=true 와 출력을 반환한다")
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
    @DisplayName("서버 에러 발생 시 success=false 와 에러 메시지를 반환한다")
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
    @DisplayName("Google 빌트인 도구가 있을 경우 X-Google-Access-Token 헤더가 주입된다")
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
    @DisplayName("Google 빌트인 도구가 없으면 X-Google-Access-Token 헤더가 주입되지 않는다")
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
    @DisplayName("Google 빌트인 도구가 있어도 userId가 없으면 토큰이 주입되지 않는다")
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

    // ── Notion 빌트인 도구 케이스 ─────────────────────────────────────────────

    @Test
    @DisplayName("Notion 빌트인 도구가 있고 credentialId가 있으면 X-Notion-Token 헤더가 주입된다")
    void execute_withNotionBuiltinToolLegacyCredentialId_injectsNotionTokenHeader() throws InterruptedException {
        // given
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        Node node = buildAgentNodeWithTools("노션에 정리해줘", "CLAUDE", "cred-id",
            List.of(Map.of("name", "builtin:notion_create_page", "credentialId", "notion-cred-id")));
        ExecutionCursor cursor = buildCursor();

        // when
        ExecutorResult result = executor.execute(node, Collections.emptyMap(), cursor);

        // then
        assertThat(result.isSuccess()).isTrue();
        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Notion-Token")).isEqualTo("decrypted-api-key");
    }

    @Test
    @DisplayName("Notion 빌트인 도구의 auth credentialId로 X-Notion-Token 헤더가 주입된다")
    void execute_withNotionBuiltinToolAuthCredential_injectsNotionTokenHeader() throws InterruptedException {
        // given
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        Node node = buildAgentNodeWithTools("노션에 정리해줘", "CLAUDE", "llm-cred-id",
            List.of(Map.of(
                "name", "builtin:notion_create_page",
                "auth", Map.of(
                    "type", "credential",
                    "credentialId", "notion-cred-id"
                )
            )));
        ExecutionCursor cursor = buildCursor();

        // when
        ExecutorResult result = executor.execute(node, Collections.emptyMap(), cursor);

        // then
        assertThat(result.isSuccess()).isTrue();
        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Notion-Token")).isEqualTo("decrypted-api-key");
        verify(credentialProvider).getDecryptedApiKey("llm-cred-id");
        verify(credentialProvider).getDecryptedApiKey("notion-cred-id");
    }

    @Test
    @DisplayName("Notion 빌트인 도구의 auth secret value로 X-Notion-Token 헤더가 주입된다")
    void execute_withNotionBuiltinToolAuthSecret_injectsNotionTokenHeader() throws InterruptedException {
        // given
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        Node node = buildAgentNodeWithTools("노션에 정리해줘", "CLAUDE", "llm-cred-id",
            List.of(Map.of(
                "name", "builtin:notion_create_page",
                "auth", Map.of(
                    "type", "secret",
                    "value", "ntn_test_token"
                )
            )));
        ExecutionCursor cursor = buildCursor();

        // when
        ExecutorResult result = executor.execute(node, Collections.emptyMap(), cursor);

        // then
        assertThat(result.isSuccess()).isTrue();
        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Notion-Token")).isEqualTo("ntn_test_token");
        verify(credentialProvider).getDecryptedApiKey("llm-cred-id");
        verify(credentialProvider, never()).getDecryptedApiKey("ntn_test_token");
    }

    @Test
    @DisplayName("Notion 빌트인 도구가 없으면 X-Notion-Token 헤더가 포함되지 않는다")
    void execute_withoutNotionTool_noNotionTokenHeader() throws InterruptedException {
        // given
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        Node node = buildAgentNodeWithTools("웹 검색해줘", "CLAUDE", "cred-id",
            List.of(Map.of("name", "builtin:http_fetch")));
        ExecutionCursor cursor = buildCursor();

        // when
        executor.execute(node, Collections.emptyMap(), cursor);

        // then
        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Notion-Token")).isNull();
    }

    @Test
    @DisplayName("Notion 빌트인 도구가 있지만 credentialId가 없으면 X-Notion-Token 헤더가 포함되지 않는다")
    void execute_withNotionToolButNoCredentialId_noNotionTokenHeader() throws InterruptedException {
        // given — credentialId 키 없이 name만 있는 tools config
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        Node node = buildAgentNodeWithTools("노션 읽어줘", "CLAUDE", "cred-id",
            List.of(Map.of("name", "builtin:notion_read_page")));  // credentialId 없음
        ExecutionCursor cursor = buildCursor();

        // when
        executor.execute(node, Collections.emptyMap(), cursor);

        // then — credentialId 없으면 토큰 조회 없이 헤더 미포함으로 진행
        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Notion-Token")).isNull();
    }

    @Test
    @DisplayName("discord 도구의 webhookCredentialId로 복호화된 webhook_url을 도구 config에 주입한다")
    void execute_withWebhookCredential_injectsWebhookUrlIntoToolConfig() throws InterruptedException {
        // given — webhookCredentialId를 해결해 URL을 돌려주는 provider로 executor 재구성
        UUID userId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        String webhookUrl = "https://discord.com/api/webhooks/123/secret-token";

        WebhookCredentialProvider webhookProvider = mock(WebhookCredentialProvider.class);
        when(webhookProvider.resolveWebhookUrl(credentialId, userId))
            .thenReturn(java.util.Optional.of(webhookUrl));

        AgentNodeExecutor exec = new AgentNodeExecutor(
            mockWebServer.url("/").toString(),
            credentialProvider,
            googleTokenProvider,
            new ToolAuthResolver(credentialProvider, notionTokenProvider, gitHubTokenProvider),
            new StubMcpCatalogProvider(),
            webhookProvider,
            uid -> null,
            betaPlatformProvider,
            30
        );

        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        Map<String, Object> discordTool = new java.util.HashMap<>();
        discordTool.put("name", "discord");
        discordTool.put("config", new java.util.HashMap<>(Map.of("webhookCredentialId", credentialId.toString())));
        Node node = buildAgentNodeWithTools("디스코드로 보내줘", "CLAUDE", "cred-id",
            List.of(discordTool));
        ExecutionCursor cursor = buildCursorWithUserId(userId);

        // when
        exec.execute(node, Collections.emptyMap(), cursor);

        // then — 요청 body의 discord 도구 config에 webhook_url이 주입되어 있어야 함
        RecordedRequest recorded = mockWebServer.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("webhook_url");
        assertThat(body).contains(webhookUrl);
    }

    // ── 베타 플랫폼 키 케이스 ─────────────────────────────────────────────────

    private static final String SUCCESS_RESPONSE_WITH_USAGE =
        "{\"success\":true,\"output\":\"완료\",\"metadata\":null,\"errorMessage\":null,"
            + "\"usage\":{\"promptTokens\":100,\"completionTokens\":50,\"totalTokens\":150}}";

    private AgentNodeExecutor buildExecutorWithBetaProvider(BetaPlatformProvider provider) {
        return buildExecutor(provider, uid -> null);
    }

    private AgentNodeExecutor buildExecutor(BetaPlatformProvider betaProvider, UserRoleProvider roleProvider) {
        return new AgentNodeExecutor(
            mockWebServer.url("/").toString(),
            credentialProvider,
            googleTokenProvider,
            new ToolAuthResolver(credentialProvider, notionTokenProvider, gitHubTokenProvider),
            new StubMcpCatalogProvider(),
            new StubWebhookCredentialProvider(),
            roleProvider,
            betaProvider,
            30
        );
    }

    @Test
    @DisplayName("credentialId 없음 + 베타 자격 - X-Key-Mode:platform, X-LLM-Provider:GEMINI, 키 헤더 없음")
    void execute_credentialIdMissingAndBetaEligible_usesPlatformKeyHeaders() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        BetaPlatformProvider betaProvider = mock(BetaPlatformProvider.class);
        when(betaProvider.isBetaEligible(userId)).thenReturn(true);
        AgentNodeExecutor exec = buildExecutorWithBetaProvider(betaProvider);

        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE_WITH_USAGE));

        Node node = buildAgentNodeNoCredential("요약해줘", "CLAUDE");
        ExecutorResult result = exec.execute(node, Collections.emptyMap(), buildCursorWithUserId(userId));

        assertThat(result.isSuccess()).isTrue();
        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Key-Mode")).isEqualTo("platform");
        assertThat(recorded.getHeader("X-LLM-Provider")).isEqualTo("GEMINI");
        assertThat(recorded.getHeader("X-LLM-Api-Key")).isNull();

        verify(betaProvider).reserveQuota(userId);
        verify(betaProvider).recordTokens(userId, 150L);
        verify(credentialProvider, never()).getDecryptedApiKey(any());
    }

    @Test
    @DisplayName("credentialId 있음(BYOK) - 베타 자격이어도 platform 미적용, 기존 키 헤더 유지")
    void execute_credentialIdPresent_ignoresBetaEvenIfEligible() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        BetaPlatformProvider betaProvider = mock(BetaPlatformProvider.class);
        when(betaProvider.isBetaEligible(userId)).thenReturn(true);
        AgentNodeExecutor exec = buildExecutorWithBetaProvider(betaProvider);

        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        Node node = buildAgentNode("요약해줘", "CLAUDE", "cred-id-byok");
        ExecutorResult result = exec.execute(node, Collections.emptyMap(), buildCursorWithUserId(userId));

        assertThat(result.isSuccess()).isTrue();
        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Key-Mode")).isNull();
        assertThat(recorded.getHeader("X-LLM-Provider")).isEqualTo("CLAUDE");
        assertThat(recorded.getHeader("X-LLM-Api-Key")).isEqualTo("decrypted-api-key");

        verify(betaProvider, never()).reserveQuota(any());
        verify(betaProvider, never()).recordTokens(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("credentialId 없음 + 베타 비자격 - 기존 keyless(self-hosted) 경로 유지, platform 헤더 없음")
    void execute_credentialIdMissingAndBetaNotEligible_keepsExistingKeylessBehavior() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        BetaPlatformProvider betaProvider = mock(BetaPlatformProvider.class);
        when(betaProvider.isBetaEligible(userId)).thenReturn(false);
        AgentNodeExecutor exec = buildExecutorWithBetaProvider(betaProvider);

        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        Node node = buildAgentNodeNoCredential("요약해줘", "CLAUDE");
        ExecutorResult result = exec.execute(node, Collections.emptyMap(), buildCursorWithUserId(userId));

        assertThat(result.isSuccess()).isTrue();
        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Key-Mode")).isNull();
        assertThat(recorded.getHeader("X-LLM-Provider")).isEqualTo("CLAUDE");
        assertThat(recorded.getHeader("X-LLM-Api-Key")).isNull();

        verify(betaProvider, never()).reserveQuota(any());
        verify(betaProvider, never()).recordTokens(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("베타 쿼터 초과 - BETA_QUOTA_EXCEEDED가 노드 실행 실패로 전파된다")
    void execute_betaQuotaExceeded_propagatesAsExecutionFailure() {
        UUID userId = UUID.randomUUID();
        BetaPlatformProvider betaProvider = mock(BetaPlatformProvider.class);
        when(betaProvider.isBetaEligible(userId)).thenReturn(true);
        org.mockito.Mockito.doThrow(
            new com.ieum.common.exception.CustomException(com.ieum.common.exception.ErrorCode.BETA_QUOTA_EXCEEDED)
        ).when(betaProvider).reserveQuota(userId);
        AgentNodeExecutor exec = buildExecutorWithBetaProvider(betaProvider);

        Node node = buildAgentNodeNoCredential("요약해줘", "CLAUDE");
        ExecutorResult result = exec.execute(node, Collections.emptyMap(), buildCursorWithUserId(userId));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo(
            com.ieum.common.exception.ErrorCode.BETA_QUOTA_EXCEEDED.getMessage());
        assertThat(mockWebServer.getRequestCount()).isZero();
        // reserveQuota 자체가 실패(쿼터 초과)했으니 INCR이 반영된 요청이 아니다 — 환불 대상 아님
        verify(betaProvider, never()).releaseDailyCall(any());
    }

    @Test
    @DisplayName("recordTokens 실패(Redis 장애) - 이미 성공한 AI 호출은 실패로 뒤집히지 않는다")
    void execute_recordTokensThrows_stillReturnsSuccess() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        BetaPlatformProvider betaProvider = mock(BetaPlatformProvider.class);
        when(betaProvider.isBetaEligible(userId)).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("Redis 연결 실패"))
            .when(betaProvider).recordTokens(userId, 150L);
        AgentNodeExecutor exec = buildExecutorWithBetaProvider(betaProvider);

        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE_WITH_USAGE));

        Node node = buildAgentNodeNoCredential("요약해줘", "CLAUDE");
        ExecutorResult result = exec.execute(node, Collections.emptyMap(), buildCursorWithUserId(userId));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput().get("output")).isEqualTo("완료");
        verify(betaProvider).recordTokens(userId, 150L);
    }

    @Test
    @DisplayName("credentialId 없음 + userId 없음 - 베타 자격 조회 없이 기존 keyless 경로 유지")
    void execute_credentialIdMissingAndNoUserId_skipsBetaCheck() throws InterruptedException {
        BetaPlatformProvider betaProvider = mock(BetaPlatformProvider.class);
        AgentNodeExecutor exec = buildExecutorWithBetaProvider(betaProvider);

        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        Node node = buildAgentNodeNoCredential("요약해줘", "CLAUDE");
        exec.execute(node, Collections.emptyMap(), buildCursor()); // userId 없음

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Key-Mode")).isNull();
        verify(betaProvider, never()).isBetaEligible(any());
    }

    // ── 베타 일일 카운터 환불 (reserve-then-release) ────────────────────────────

    @Test
    @DisplayName("베타 platform 키 사용 + agent 응답 실패(success=false) - 일일 카운터를 환불한다")
    void execute_betaPlatformKeyAndAgentResponseFailure_releasesDailyCall() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        BetaPlatformProvider betaProvider = mock(BetaPlatformProvider.class);
        when(betaProvider.isBetaEligible(userId)).thenReturn(true);
        when(betaProvider.reserveQuota(userId)).thenReturn("beta:calls:" + userId + ":test-key");
        AgentNodeExecutor exec = buildExecutorWithBetaProvider(betaProvider);

        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(500)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"error\":\"Internal Server Error\"}"));

        Node node = buildAgentNodeNoCredential("요약해줘", "CLAUDE");
        ExecutorResult result = exec.execute(node, Collections.emptyMap(), buildCursorWithUserId(userId));

        assertThat(result.isSuccess()).isFalse();
        verify(betaProvider).reserveQuota(userId);
        // reserve가 반환한 바로 그 키로 환불한다(자정 경계에도 동일 날짜 키를 보장하는 A-2 강건화)
        verify(betaProvider).releaseDailyCall("beta:calls:" + userId + ":test-key");
    }

    @Test
    @DisplayName("베타 platform 키 사용 + agent 응답 성공 - 일일 카운터를 환불하지 않는다")
    void execute_betaPlatformKeyAndAgentResponseSuccess_doesNotReleaseDailyCall() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        BetaPlatformProvider betaProvider = mock(BetaPlatformProvider.class);
        when(betaProvider.isBetaEligible(userId)).thenReturn(true);
        AgentNodeExecutor exec = buildExecutorWithBetaProvider(betaProvider);

        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        Node node = buildAgentNodeNoCredential("요약해줘", "CLAUDE");
        ExecutorResult result = exec.execute(node, Collections.emptyMap(), buildCursorWithUserId(userId));

        assertThat(result.isSuccess()).isTrue();
        verify(betaProvider, never()).releaseDailyCall(any());
    }

    @Test
    @DisplayName("베타 platform 키 예약 후 예외로 실행 실패(outer catch) - 일일 카운터를 환불한다")
    void execute_betaPlatformKeyAndUnexpectedException_releasesDailyCall() {
        // 사전작업(google/tool/mcp/webhook)은 reserveQuota보다 먼저 실행되므로, reserve 이후에만
        // 발생하는 실패를 재현하려면 agent 호출 자체가 예외로 끝나야 한다 — 연결 불가 포트로 그 상황을 만든다.
        UUID userId = UUID.randomUUID();
        BetaPlatformProvider betaProvider = mock(BetaPlatformProvider.class);
        when(betaProvider.isBetaEligible(userId)).thenReturn(true);
        when(betaProvider.reserveQuota(userId)).thenReturn("beta:calls:" + userId + ":test-key");
        AgentNodeExecutor exec = new AgentNodeExecutor(
            "http://127.0.0.1:1", // 아무도 리스닝하지 않는 포트 — 즉시 연결 거부(WebClientRequestException)
            credentialProvider,
            googleTokenProvider,
            new ToolAuthResolver(credentialProvider, notionTokenProvider, gitHubTokenProvider),
            new StubMcpCatalogProvider(),
            new StubWebhookCredentialProvider(),
            uid -> null,
            betaProvider,
            5
        );

        Node node = buildAgentNodeNoCredential("요약해줘", "CLAUDE");
        ExecutorResult result = exec.execute(node, Collections.emptyMap(), buildCursorWithUserId(userId));

        assertThat(result.isSuccess()).isFalse();
        verify(betaProvider).reserveQuota(userId);
        verify(betaProvider).releaseDailyCall("beta:calls:" + userId + ":test-key");
    }

    // ── self-hosted 우선순위 (ChatService.isSelfHostedEligible과 동일 판정) ─────

    @Test
    @DisplayName("self-hosted 자격(ROLE_ADMIN)이면 베타 자격이 있어도 베타 분기로 가지 않는다")
    void execute_selfHostedEligible_skipsBetaBranchEvenIfBetaEligible() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        BetaPlatformProvider betaProvider = mock(BetaPlatformProvider.class);
        when(betaProvider.isBetaEligible(userId)).thenReturn(true);
        AgentNodeExecutor exec = buildExecutor(betaProvider, uid -> "ROLE_ADMIN");

        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        Node node = buildAgentNodeNoCredential("요약해줘", "CLAUDE");
        ExecutorResult result = exec.execute(node, Collections.emptyMap(), buildCursorWithUserId(userId));

        assertThat(result.isSuccess()).isTrue();
        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Key-Mode")).isNull();
        assertThat(recorded.getHeader("X-LLM-Provider")).isEqualTo("CLAUDE");
        assertThat(recorded.getHeader("X-LLM-Api-Key")).isNull();
        assertThat(recorded.getHeader("X-User-Role")).isEqualTo("ROLE_ADMIN");

        verify(betaProvider, never()).isBetaEligible(any());
        verify(betaProvider, never()).reserveQuota(any());
    }

    @Test
    @DisplayName("self-hosted 자격 없는(ROLE_USER) 베타 대상자는 기존대로 베타 platform 분기로 간다")
    void execute_notSelfHostedEligible_stillUsesBetaBranch() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        BetaPlatformProvider betaProvider = mock(BetaPlatformProvider.class);
        when(betaProvider.isBetaEligible(userId)).thenReturn(true);
        AgentNodeExecutor exec = buildExecutor(betaProvider, uid -> "ROLE_USER");

        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(SUCCESS_RESPONSE));

        Node node = buildAgentNodeNoCredential("요약해줘", "CLAUDE");
        ExecutorResult result = exec.execute(node, Collections.emptyMap(), buildCursorWithUserId(userId));

        assertThat(result.isSuccess()).isTrue();
        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Key-Mode")).isEqualTo("platform");
        assertThat(recorded.getHeader("X-LLM-Provider")).isEqualTo("GEMINI");

        verify(betaProvider).reserveQuota(userId);
    }
}
