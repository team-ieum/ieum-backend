package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionContext;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.FailureKind;
import com.ieum.workflowcore.engine.IdempotencyMode;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.engine.RetryPolicy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ACTION 노드 실행기 — agent {@code POST /v1/actions/execute} 계약과 실패 분류를 고정한다. */
class ActionNodeExecutorTest {

    private static final String TOOL_KEY = "builtin:github_create_issue";
    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T000/B000/secret-token";

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();

    private MockWebServer mockWebServer;
    private GoogleTokenProvider googleTokenProvider;
    private NotionTokenProvider notionTokenProvider;
    private GitHubTokenProvider gitHubTokenProvider;
    private WebhookCredentialProvider webhookCredentialProvider;
    private IdempotencyStore idempotencyStore;
    private ActionNodeExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        googleTokenProvider = mock(GoogleTokenProvider.class);
        notionTokenProvider = mock(NotionTokenProvider.class);
        gitHubTokenProvider = mock(GitHubTokenProvider.class);
        webhookCredentialProvider = mock(WebhookCredentialProvider.class);
        idempotencyStore = mock(IdempotencyStore.class);
        when(idempotencyStore.markInFlight(any(), any())).thenReturn(true);
        when(gitHubTokenProvider.getAccessToken(userId)).thenReturn(Optional.of("gh-token"));
        executor = executorFor(mockWebServer.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    private ActionNodeExecutor executorFor(String baseUrl) {
        return new ActionNodeExecutor(
            baseUrl,
            new ToolCallPreparer(
                googleTokenProvider,
                new ToolAuthResolver(mock(CredentialProvider.class), notionTokenProvider, gitHubTokenProvider),
                webhookCredentialProvider),
            uid -> "ROLE_USER",
            idempotencyStore,
            30);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private static Map<String, Object> tool(String name, Map<String, Object> config) {
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", name);
        tool.put("config", config);
        return tool;
    }

    private static Node actionNode(List<Object> tools) {
        Map<String, Object> config = new HashMap<>();
        config.put("tools", tools);
        return new Node("node-1", NodeType.ACTION, "이슈 만들기", config);
    }

    private static Node githubNode() {
        return actionNode(List.of(tool(TOOL_KEY,
            new HashMap<>(Map.of("owner", "ieum", "repo", "demo", "title", "버그")))));
    }

    private ExecutionCursor cursor(UUID user) {
        ExecutionContext context = new ExecutionContext();
        context.setUserId(user);
        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setAllNodes(Collections.emptyList());
        cursor.setAllEdges(Collections.emptyList());
        cursor.setContext(context);
        return cursor;
    }

    private void enqueue(int status, String body) {
        mockWebServer.enqueue(new MockResponse().setResponseCode(status)
            .setHeader("Content-Type", "application/json").setBody(body));
    }

    private void enqueueSuccess() {
        enqueue(200, "{\"success\":true,\"output\":{\"number\":7,\"url\":\"https://github.com/o/r/issues/7\"}}");
    }

    private static RetryPolicy policy(int maxAttempts, IdempotencyMode mode) {
        return new RetryPolicy(maxAttempts, 0, 1.0, 0, false, null, List.of(), mode);
    }

    private static final Map<String, Object> NO_INPUT = Collections.emptyMap();

    // ── 성공: 출력 모양과 요청 계약 ──────────────────────────────────────────

    @Test
    @DisplayName("256KB를 넘는 성공 응답도 디코딩돼 그대로 노드 출력이 된다 — 기본 codec 한도에 묶이지 않는다")
    void success_largeResponseOver256KbIsDecoded() {
        String content = "a".repeat(300 * 1024);
        enqueue(200, "{\"success\":true,\"output\":{\"content\":\"" + content + "\"}}");

        ExecutorResult result = executor.execute(githubNode(), NO_INPUT, cursor(userId));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).containsExactly(Map.entry("content", content));
    }

    @Test
    @DisplayName("노드 출력은 agent output dict 그대로다 — AI 노드의 {output, metadata} 래퍼도 usage도 없다")
    void success_outputIsAgentOutputDictAsIs() throws Exception {
        enqueue(200, "{\"success\":true,\"output\":{\"issues\":[{\"number\":1,\"title\":\"첫 이슈\"}],\"count\":1}}");

        ExecutorResult result = executor.execute(githubNode(), NO_INPUT, cursor(userId));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).containsOnlyKeys("issues", "count");
        assertThat(result.getOutput().get("count")).isEqualTo(1);
        assertThat(((List<?>) result.getOutput().get("issues")).get(0))
            .isEqualTo(Map.of("number", 1, "title", "첫 이슈"));
        assertThat(result.getUsage()).isNull();
        assertThat(mockWebServer.takeRequest().getPath()).isEqualTo("/v1/actions/execute");
    }

    @Test
    @DisplayName("요청 body는 {nodeId, toolKey, config}뿐이고 config는 tools[0].config다")
    void request_bodyIsNodeIdToolKeyConfigOnly() throws Exception {
        enqueueSuccess();

        executor.execute(githubNode(), NO_INPUT, cursor(userId));

        JsonNode body = mapper.readTree(mockWebServer.takeRequest().getBody().readUtf8());
        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("nodeId", "toolKey", "config");
        assertThat(body.get("nodeId").asText()).isEqualTo("node-1");
        assertThat(body.get("toolKey").asText()).isEqualTo(TOOL_KEY);
        assertThat(body.get("config")).isEqualTo(mapper.valueToTree(
            Map.of("owner", "ieum", "repo", "demo", "title", "버그")));
    }

    @Test
    @DisplayName("헤더: userId·역할·nodeId·traceId·도구 토큰을 싣고 LLM 헤더·베타 키 모드는 보내지 않는다")
    void request_headers() throws Exception {
        enqueueSuccess();
        ExecutionCursor cursor = cursor(userId);
        cursor.getContext().setTraceId("abcdef0123456789abcdef0123456789");

        executor.execute(githubNode(), NO_INPUT, cursor);

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-User-Id")).isEqualTo(userId.toString());
        assertThat(recorded.getHeader("X-User-Role")).isEqualTo("ROLE_USER");
        assertThat(recorded.getHeader("X-Node-Id")).isEqualTo("node-1");
        assertThat(recorded.getHeader("X-Trace-Id")).isEqualTo("abcdef0123456789abcdef0123456789");
        assertThat(recorded.getHeader("X-GitHub-Token")).isEqualTo("gh-token");
        assertThat(recorded.getHeader("X-LLM-Provider")).isNull();
        assertThat(recorded.getHeader("X-LLM-Api-Key")).isNull();
        assertThat(recorded.getHeader("X-Key-Mode")).isNull();
        assertThat(recorded.getHeader("X-Idempotency-Key")).isNull();
    }

    @Test
    @DisplayName("userId·traceId가 없으면 해당 헤더를 보내지 않는다")
    void request_withoutUserOrTrace_omitsHeaders() throws Exception {
        enqueueSuccess();

        executor.execute(githubNode(), NO_INPUT, cursor(null));

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-User-Id")).isNull();
        assertThat(recorded.getHeader("X-User-Role")).isNull();
        assertThat(recorded.getHeader("X-Trace-Id")).isNull();
        assertThat(recorded.getHeader("X-Node-Id")).isEqualTo("node-1");
    }

    @Test
    @DisplayName("tools[0].config의 참조식을 앞 노드 출력으로 치환해 보낸다 — issues.0.title")
    void request_rendersReferencesInToolConfig() throws Exception {
        enqueueSuccess();
        ExecutionCursor cursor = cursor(userId);
        cursor.getContext().setNodeOutput("list",
            Map.of("issues", List.of(Map.of("number", 1, "title", "첫 이슈"))));
        Node node = actionNode(List.of(tool(TOOL_KEY,
            new HashMap<>(Map.of("title", "{{nodes.list.output.issues.0.title}}")))));

        executor.execute(node, NO_INPUT, cursor);

        JsonNode body = mapper.readTree(mockWebServer.takeRequest().getBody().readUtf8());
        assertThat(body.get("config").get("title").asText()).isEqualTo("첫 이슈");
    }

    @Test
    @DisplayName("Google 도구면 액세스 토큰을 X-Google-Access-Token으로 보낸다")
    void request_googleTool_sendsAccessToken() throws Exception {
        enqueueSuccess();
        when(googleTokenProvider.getValidAccessToken(userId)).thenReturn("google-access");
        Node node = actionNode(List.of(tool("builtin:google_sheets_read", new HashMap<>())));

        executor.execute(node, NO_INPUT, cursor(userId));

        assertThat(mockWebServer.takeRequest().getHeader("X-Google-Access-Token")).isEqualTo("google-access");
    }

    @Test
    @DisplayName("agent가 output을 비워 success만 돌려줘도 NPE 없이 빈 출력으로 성공한다")
    void success_withoutOutput_returnsEmptyOutput() {
        enqueue(200, "{\"success\":true}");

        ExecutorResult result = executor.execute(githubNode(), NO_INPUT, cursor(userId));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEmpty();
    }

    @Test
    @DisplayName("본문이 빈 200 응답은 실패로 처리한다")
    void emptyResponseBody_isFailure() {
        enqueue(200, "");

        ExecutorResult result = executor.execute(githubNode(), NO_INPUT, cursor(userId));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotBlank();
    }

    // ── 실패 분류와 재시도 ───────────────────────────────────────────────────

    @ParameterizedTest(name = "errorCode {0} → {1}")
    @CsvSource({
        "ACTION_TOOL_FAILED, CLIENT_ERROR",
        "AGENT_EXECUTION_FAILED, UNKNOWN",
        "AGENT_TIMEOUT, TIMEOUT",
        "RATE_LIMITED, RATE_LIMIT"
    })
    @DisplayName("agent가 success=false로 돌려준 errorCode를 FailureKind로 분류하고 메시지를 그대로 싣는다")
    void failure_errorCodeIsClassified(String errorCode, FailureKind expected) {
        enqueue(200, "{\"success\":false,\"output\":null,\"errorMessage\":\"GitHub API 오류 (422): 검증 실패\","
            + "\"errorCode\":\"" + errorCode + "\"}");

        ExecutorResult result = executor.execute(githubNode(), NO_INPUT, cursor(userId));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureKind()).isEqualTo(expected);
        assertThat(result.getErrorMessage()).isEqualTo("GitHub API 오류 (422): 검증 실패");
    }

    @ParameterizedTest(name = "HTTP {0} → {1}")
    @CsvSource({"400, CLIENT_ERROR", "404, CLIENT_ERROR", "429, RATE_LIMIT", "503, SERVER_ERROR"})
    @DisplayName("agent가 본문 없이 HTTP 오류만 주면 상태로 분류한다 — 알 수 없는 toolKey의 400은 재시도 대상이 아니다")
    void failure_httpStatusIsClassified(int status, FailureKind expected) {
        enqueue(status, "{\"detail\":\"UNKNOWN_TOOL\"}");

        ExecutorResult result = executor.execute(githubNode(), NO_INPUT, cursor(userId));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureKind()).isEqualTo(expected);
        assertThat(result.getErrorMessage()).contains("HTTP " + status).contains("UNKNOWN_TOOL");
    }

    @Test
    @DisplayName("재시도를 선언해도 도구 오류·알 수 없는 toolKey는 재시도 대상이 아니고, 5xx만 재시도 대상이다")
    void declaredRetry_neverRetriesToolFailureOrUnknownTool() {
        RetryPolicy retry = policy(3, IdempotencyMode.HEADER);
        enqueue(200, "{\"success\":false,\"errorMessage\":\"422\",\"errorCode\":\"ACTION_TOOL_FAILED\"}");
        enqueue(400, "{\"detail\":\"UNKNOWN_TOOL\"}");
        enqueue(503, "{}");

        FailureKind toolFailed = executor.execute(githubNode(), NO_INPUT, cursor(userId)).getFailureKind();
        FailureKind unknownTool = executor.execute(githubNode(), NO_INPUT, cursor(userId)).getFailureKind();
        FailureKind serverDown = executor.execute(githubNode(), NO_INPUT, cursor(userId)).getFailureKind();

        assertThat(retry.retryable(toolFailed)).isFalse();
        assertThat(retry.retryable(unknownTool)).isFalse();
        assertThat(retry.retryable(serverDown)).isTrue();
    }

    @Test
    @DisplayName("agent에 연결할 수 없으면 NETWORK 실패다")
    void failure_connectionRefused_isNetwork() {
        ActionNodeExecutor unreachable = executorFor("http://127.0.0.1:1");

        ExecutorResult result = unreachable.execute(githubNode(), NO_INPUT, cursor(userId));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureKind()).isEqualTo(FailureKind.NETWORK);
    }

    // ── 멱등성 (쓰기 도구의 재시도 중복 방지) ────────────────────────────────

    @Test
    @DisplayName("HEADER 모드 + 재시도 선언: 모든 회차가 같은 X-Idempotency-Key를 보낸다")
    void headerMode_sameKeyAcrossAttempts() throws Exception {
        enqueueSuccess();
        enqueueSuccess();
        RetryPolicy retry = policy(3, IdempotencyMode.HEADER);

        executor.execute(githubNode(), NO_INPUT, cursor(userId),
            new NodeExecutor.NodeAttempt(1, "fixed-key", retry));
        executor.execute(githubNode(), NO_INPUT, cursor(userId),
            new NodeExecutor.NodeAttempt(2, "fixed-key", retry));

        assertThat(mockWebServer.takeRequest().getHeader("X-Idempotency-Key")).isEqualTo("fixed-key");
        assertThat(mockWebServer.takeRequest().getHeader("X-Idempotency-Key")).isEqualTo("fixed-key");
    }

    @Test
    @DisplayName("재시도가 꺼져 있으면(maxAttempts=1) HEADER 기본값이어도 헤더를 보내지 않는다")
    void headerMode_disabledPolicy_noHeader() throws Exception {
        enqueueSuccess();

        executor.execute(githubNode(), NO_INPUT, cursor(userId),
            new NodeExecutor.NodeAttempt(1, "fixed-key", policy(1, IdempotencyMode.HEADER)));

        assertThat(mockWebServer.takeRequest().getHeader("X-Idempotency-Key")).isNull();
    }

    @Test
    @DisplayName("MARKER 모드: 마커가 이미 있으면 agent 호출 없이 CLIENT_ERROR 실패를 반환한다")
    void markerMode_blockedWhenAlreadyInFlight() {
        RetryPolicy marker = policy(3, IdempotencyMode.MARKER);
        when(idempotencyStore.markInFlight("dup-key", NodeExecutor.markerTtl(marker))).thenReturn(false);

        ExecutorResult result = executor.execute(githubNode(), NO_INPUT, cursor(userId),
            new NodeExecutor.NodeAttempt(1, "dup-key", marker));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureKind()).isEqualTo(FailureKind.CLIENT_ERROR);
        assertThat(mockWebServer.getRequestCount()).isZero();
    }

    // ── 쓸 수 없는 ACTION 정의 ───────────────────────────────────────────────

    @Test
    @DisplayName("tools가 없거나 비었거나 tools[0]에 name이 없으면 agent를 부르지 않고 CLIENT_ERROR로 끝난다")
    void invalidDefinition_failsFastWithoutCallingAgent() {
        Map<String, Object> noTools = new HashMap<>();
        Map<String, Object> toolsNotList = new HashMap<>(Map.of("tools", "builtin:github_list_issues"));
        Map<String, Object> emptyTools = new HashMap<>(Map.of("tools", List.of()));
        Node missingName = actionNode(List.of(tool(null, new HashMap<>())));
        Node blankName = actionNode(List.of(tool("  ", new HashMap<>())));
        Node numberEntry = actionNode(List.of(42));

        List<Node> nodes = List.of(
            new Node("n1", NodeType.ACTION, "tools 없음", noTools),
            new Node("n2", NodeType.ACTION, "tools가 목록 아님", toolsNotList),
            new Node("n3", NodeType.ACTION, "tools 비어 있음", emptyTools),
            missingName, blankName, numberEntry);
        for (Node node : nodes) {
            ExecutorResult result = executor.execute(node, NO_INPUT, cursor(userId));

            assertThat(result.isSuccess()).as(node.getLabel()).isFalse();
            assertThat(result.getFailureKind()).as(node.getLabel()).isEqualTo(FailureKind.CLIENT_ERROR);
            assertThat(result.getErrorMessage()).as(node.getLabel()).contains("tools");
        }
        assertThat(mockWebServer.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("node.config 자체가 null이어도 NPE 없이 CLIENT_ERROR다")
    void nullConfig_isClientError() {
        ExecutorResult result = executor.execute(
            new Node("n1", NodeType.ACTION, "config 없음", null), NO_INPUT, cursor(userId));

        assertThat(result.getFailureKind()).isEqualTo(FailureKind.CLIENT_ERROR);
        assertThat(mockWebServer.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("도구가 둘 이상이어도 첫 도구만 실행하고 둘째 도구의 토큰·헤더는 보내지 않는다")
    void onlyFirstToolIsUsed() throws Exception {
        enqueueSuccess();
        when(notionTokenProvider.getAccessToken(userId)).thenReturn(Optional.of("notion-token"));
        Node node = actionNode(List.of(
            tool(TOOL_KEY, new HashMap<>(Map.of("title", "버그"))),
            tool("builtin:notion_search", new HashMap<>())));

        executor.execute(node, NO_INPUT, cursor(userId));

        RecordedRequest recorded = mockWebServer.takeRequest();
        JsonNode body = mapper.readTree(recorded.getBody().readUtf8());
        assertThat(body.get("toolKey").asText()).isEqualTo(TOOL_KEY);
        assertThat(recorded.getHeader("X-GitHub-Token")).isEqualTo("gh-token");
        assertThat(recorded.getHeader("X-Notion-Token")).isNull();
        verify(notionTokenProvider, never()).getAccessToken(any());
    }

    // ── 웹훅 URL 원문 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("slack 도구는 복호화한 webhook_url을 agent 요청에만 싣고, 노드 config·input에는 남기지 않는다")
    void slackTool_webhookUrlOnlyInRequest() throws Exception {
        enqueue(200, "{\"success\":true,\"output\":{\"ok\":true}}");
        UUID credentialId = UUID.randomUUID();
        when(webhookCredentialProvider.resolveWebhookUrl(credentialId, userId))
            .thenReturn(Optional.of(WEBHOOK_URL));
        Map<String, Object> toolConfig = new HashMap<>(Map.of(
            "webhookCredentialId", credentialId.toString(), "message", "안녕"));
        Node node = actionNode(List.of(tool("slack", toolConfig)));
        Map<String, Object> input = new HashMap<>(node.getConfig());

        ExecutorResult result = executor.execute(node, input, cursor(userId));

        assertThat(result.isSuccess()).isTrue();
        JsonNode body = mapper.readTree(mockWebServer.takeRequest().getBody().readUtf8());
        assertThat(body.get("toolKey").asText()).isEqualTo("slack");
        assertThat(body.get("config").get("webhook_url").asText()).isEqualTo(WEBHOOK_URL);
        assertThat(body.get("config").get("webhookCredentialId").asText()).isEqualTo(credentialId.toString());
        // node_runs 입력 로그·조회 응답으로 새지 않도록 노드 원본과 input에는 URL이 없어야 한다
        assertThat(toolConfig).doesNotContainKey("webhook_url");
        assertThat(node.getConfig().toString()).doesNotContain(WEBHOOK_URL);
        assertThat(input.toString()).doesNotContain(WEBHOOK_URL);
        assertThat(result.getOutput().toString()).doesNotContain(WEBHOOK_URL);
    }
}
