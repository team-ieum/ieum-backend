package com.ieum.workflowcore.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.config.RetryProperties;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowExecutionLog;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.engine.executor.ActionNodeExecutor;
import com.ieum.workflowcore.engine.executor.AlertNotifier;
import com.ieum.workflowcore.engine.executor.CredentialProvider;
import com.ieum.workflowcore.engine.executor.GitHubTokenProvider;
import com.ieum.workflowcore.engine.executor.GoogleTokenProvider;
import com.ieum.workflowcore.engine.executor.IdempotencyStore;
import com.ieum.workflowcore.engine.executor.NodeExecutor;
import com.ieum.workflowcore.engine.executor.NotionTokenProvider;
import com.ieum.workflowcore.engine.executor.StubWebhookCredentialProvider;
import com.ieum.workflowcore.engine.executor.ToolAuthResolver;
import com.ieum.workflowcore.engine.executor.ToolCallPreparer;
import com.ieum.workflowcore.engine.executor.TriggerNodeExecutor;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.io.IOException;
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
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ACTION 노드를 런타임에 실물로 올린 흐름 — 트리거 → 이슈 목록 → 이슈 생성(앞 노드 출력 참조).
 * 실물: {@link SyncExecutionRuntime}, {@link TriggerNodeExecutor}, {@link ActionNodeExecutor},
 * {@code ToolCallPreparer}, {@code ToolAuthResolver}. 목: 저장소·정의 로드·이벤트·알림·토큰, agent(MockWebServer).
 */
class ActionNodeRuntimeTest {

    private static final String LIST_RESPONSE =
        "{\"success\":true,\"output\":{\"issues\":[{\"number\":1,\"title\":\"첫 이슈\"}],\"count\":1}}";
    private static final String CREATE_RESPONSE =
        "{\"success\":true,\"output\":{\"number\":2,\"url\":\"https://github.com/ieum/demo/issues/2\",\"title\":\"첫 이슈\"}}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID executionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private MockWebServer mockWebServer;
    private WorkflowExecutionLogRepository logRepository;
    private WorkflowExecutionRepository executionRepository;
    private WorkflowCrudService crudService;
    private AlertNotifier alertNotifier;
    private SyncExecutionRuntime runtime;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        logRepository = mock(WorkflowExecutionLogRepository.class);
        executionRepository = mock(WorkflowExecutionRepository.class);
        crudService = mock(WorkflowCrudService.class);
        alertNotifier = mock(AlertNotifier.class);
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);

        Workflow workflow = mock(Workflow.class);
        when(workflow.getId()).thenReturn(UUID.randomUUID());
        when(workflow.getUserId()).thenReturn(userId);
        WorkflowExecution execution = mock(WorkflowExecution.class);
        when(execution.getWorkflow()).thenReturn(workflow);
        when(execution.getTraceId()).thenReturn("11112222333344445555666677778888");
        when(executionRepository.findWithWorkflowById(executionId)).thenReturn(Optional.of(execution));
        when(executionRepository.startIfNotTerminal(any(), any())).thenReturn(1);
        when(executionRepository.finishIfNotTerminal(any(), any(), anyBoolean(), any())).thenReturn(1);

        GitHubTokenProvider gitHubTokenProvider = mock(GitHubTokenProvider.class);
        when(gitHubTokenProvider.getAccessToken(userId)).thenReturn(Optional.of("gh-token"));
        ActionNodeExecutor action = new ActionNodeExecutor(
            mockWebServer.url("/").toString(),
            new ToolCallPreparer(
                mock(GoogleTokenProvider.class),
                new ToolAuthResolver(mock(CredentialProvider.class), mock(NotionTokenProvider.class),
                    gitHubTokenProvider),
                new StubWebhookCredentialProvider()),
            uid -> "ROLE_USER",
            idempotencyStore,
            30);

        List<NodeExecutor> executors = List.of(new TriggerNodeExecutor(), action);
        runtime = new SyncExecutionRuntime(objectMapper, logRepository, executionRepository, crudService,
            mock(ExecutionEventPublisher.class), executors, new RetryProperties(), idempotencyStore,
            alertNotifier);
        runtime.initExecutorMap();
        ReflectionTestUtils.setField(runtime, "parallelism", 4);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ── 정의 ────────────────────────────────────────────────────────────────

    private static Map<String, Object> node(String id, String type, Map<String, Object> config) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("label", id);
        node.put("config", config);
        return node;
    }

    private static Map<String, Object> githubTool(String toolKey, Map<String, Object> config) {
        return Map.of("name", toolKey, "config", config);
    }

    private static Map<String, Object> actionConfig(Map<String, Object> tool) {
        Map<String, Object> config = new HashMap<>();
        config.put("tools", List.of(tool));
        return config;
    }

    private static Map<String, Object> edge(String source, String target) {
        Map<String, Object> edge = new HashMap<>();
        edge.put("source", source);
        edge.put("target", target);
        return edge;
    }

    /** 트리거 → list(이슈 목록) → create(이슈 생성, title이 list 출력 참조). */
    private void stubTwoActionWorkflow(Map<String, Object> listConfig) {
        Map<String, Object> createTool = githubTool("builtin:github_create_issue", new HashMap<>(Map.of(
            "owner", "ieum", "repo", "demo", "title", "{{nodes.list.output.issues.0.title}}")));
        WorkflowDefinitionDocument doc = WorkflowDefinitionDocument.builder()
            .nodes(List.of(
                node("t", "TRIGGER", new HashMap<>(Map.of("triggerType", "MANUAL"))),
                node("list", "ACTION", listConfig),
                node("create", "ACTION", actionConfig(createTool))))
            .edges(List.of(edge("t", "list"), edge("list", "create")))
            .build();
        when(crudService.loadDefinition(any())).thenReturn(doc);
    }

    private Map<String, Object> listConfig() {
        return actionConfig(githubTool("builtin:github_list_issues",
            new HashMap<>(Map.of("owner", "ieum", "repo", "demo"))));
    }

    private void run() throws Exception {
        runtime.execute(mock(WorkflowVersion.class), executionId, new HashMap<>());
    }

    private void enqueue(int status, String body) {
        mockWebServer.enqueue(new MockResponse().setResponseCode(status)
            .setHeader("Content-Type", "application/json").setBody(body));
    }

    private WorkflowExecutionLog savedLog(String nodeId) {
        ArgumentCaptor<WorkflowExecutionLog> captor = ArgumentCaptor.forClass(WorkflowExecutionLog.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream()
            .filter(l -> nodeId.equals(l.getNodeId())).findFirst().orElseThrow();
    }

    // ── 테스트 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("이슈 목록 → 이슈 생성: 앞 노드의 issues.0.title이 다음 요청에 들어가고 node_runs에 ACTION으로 기록된다")
    void twoActions_referenceFlowsAndLogsAsAction() throws Exception {
        stubTwoActionWorkflow(listConfig());
        enqueue(200, LIST_RESPONSE);
        enqueue(200, CREATE_RESPONSE);

        run();

        verify(executionRepository).finishIfNotTerminal(
            eq(executionId), eq(ExecutionStatus.SUCCESS), eq(false), any());

        RecordedRequest listRequest = mockWebServer.takeRequest();
        assertThat(listRequest.getPath()).isEqualTo("/v1/actions/execute");
        assertThat(listRequest.getHeader("X-Node-Id")).isEqualTo("list");
        assertThat(listRequest.getHeader("X-Trace-Id")).isEqualTo("11112222333344445555666677778888");
        assertThat(listRequest.getHeader("X-GitHub-Token")).isEqualTo("gh-token");
        assertThat(listRequest.getHeader("X-Idempotency-Key")).isNull(); // 재시도 미선언 — 헤더 없음

        JsonNode createBody = objectMapper.readTree(mockWebServer.takeRequest().getBody().readUtf8());
        assertThat(createBody.get("toolKey").asText()).isEqualTo("builtin:github_create_issue");
        assertThat(createBody.get("config").get("title").asText()).isEqualTo("첫 이슈");

        for (String nodeId : List.of("list", "create")) {
            WorkflowExecutionLog saved = savedLog(nodeId);
            assertThat(saved.getNodeType()).isEqualTo(NodeType.ACTION);
            assertThat(saved.getStatus()).isEqualTo(ExecutionLogStatus.SUCCESS);
            assertThat(saved.getAttemptCount()).isEqualTo(1);
        }
        assertThat(savedLog("list").getOutputJson()).contains("\"issues\"").contains("첫 이슈");
        assertThat(savedLog("create").getOutputJson()).contains("\"number\":2");
    }

    @Test
    @DisplayName("도구 오류 본문이 255자를 넘어도 ACTION의 FAILED 이력이 남고 이후 노드는 실행되지 않는다")
    void toolFailureWithLongMessage_keepsFailedRowAndStops() throws Exception {
        stubTwoActionWorkflow(listConfig());
        String longError = "GitHub API 오류 (422): " + "{\"message\":\"Validation Failed\"}".repeat(20);
        enqueue(200, "{\"success\":false,\"errorMessage\":\"" + longError.replace("\"", "\\\"")
            + "\",\"errorCode\":\"ACTION_TOOL_FAILED\"}");

        run();

        verify(executionRepository).finishIfNotTerminal(
            eq(executionId), eq(ExecutionStatus.FAILED), eq(false), any());
        assertThat(mockWebServer.getRequestCount()).isEqualTo(1); // create는 호출되지 않았다
        WorkflowExecutionLog failed = savedLog("list");
        assertThat(failed.getNodeType()).isEqualTo(NodeType.ACTION);
        assertThat(failed.getStatus()).isEqualTo(ExecutionLogStatus.FAILED);
        assertThat(longError.length()).isGreaterThan(255);
        assertThat(failed.getErrorMessage()).hasSize(255).isEqualTo(longError.substring(0, 255));
        // 도구 오류는 재시도 대상이 아니라 소진이 아니다
        verify(alertNotifier).notifyExecutionFailed(argThat(a -> "list".equals(a.failedNodeId()) && !a.retryExhausted()));
    }

    @Test
    @DisplayName("ACTION에 retry를 선언하면 5xx를 재시도하고 두 회차가 같은 멱등 키를 보낸다")
    void declaredRetry_retriesServerErrorWithSameIdempotencyKey() throws Exception {
        Map<String, Object> config = listConfig();
        config.put("retry", Map.of("maxAttempts", 2, "backoffMs", 0, "maxBackoffMs", 0, "jitter", false));
        stubTwoActionWorkflow(config);
        enqueue(503, "{}");
        enqueue(200, LIST_RESPONSE);
        enqueue(200, CREATE_RESPONSE);

        run();

        verify(executionRepository).finishIfNotTerminal(
            eq(executionId), eq(ExecutionStatus.SUCCESS), eq(false), any());
        String firstKey = mockWebServer.takeRequest().getHeader("X-Idempotency-Key");
        String secondKey = mockWebServer.takeRequest().getHeader("X-Idempotency-Key");
        assertThat(firstKey).isNotBlank().isEqualTo(secondKey);
        assertThat(savedLog("list").getAttemptCount()).isEqualTo(2);
    }
}
