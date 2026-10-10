package com.ieum.workflowcore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.config.RetryProperties;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.NodeTestSample;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.SampleStatus;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.engine.executor.ConditionNodeExecutor;
import com.ieum.workflowcore.engine.executor.IdempotencyStore;
import com.ieum.workflowcore.engine.executor.NodeExecutor;
import com.ieum.workflowcore.engine.executor.TransformNodeExecutor;
import com.ieum.workflowcore.engine.executor.TriggerNodeExecutor;
import com.ieum.workflowcore.repository.NodeTestSampleRepository;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 노드 단일 테스트 러너 — SyncExecutionRuntime 밖에서 실행기를 직접 부른다.
 * 저장소는 (workflowId:nodeId)를 키로 하는 인메모리 페이크라 upsert·워크플로우 범위가 실제처럼 동작한다.
 */
class NodeTestRunnerTest {

    private static final Pattern TEST_KEY = Pattern.compile(
        "test-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:node-1");

    // 스프링 부트 ObjectMapper와 같은 설정 — 저장된 노드 정의의 description·position 같은 모르는 필드를 무시한다.
    private final ObjectMapper objectMapper =
        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, NodeTestSample> samples = new LinkedHashMap<>();
    private final NodeTestSampleRepository sampleRepository = fakeRepository();
    private final WorkflowCrudService crudService = mock(WorkflowCrudService.class);
    private final IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
    private final FakeExecutor http = new FakeExecutor(NodeType.HTTP);
    private final FakeExecutor ai = new FakeExecutor(NodeType.AI);
    private final NodeTestRunner runner = new NodeTestRunner(
        List.of(http, ai, new TransformNodeExecutor(), new ConditionNodeExecutor(), new TriggerNodeExecutor()),
        sampleRepository, crudService, new RetryProperties(), idempotencyStore, objectMapper);

    private final UUID userId = UUID.randomUUID();
    private final Workflow workflow = workflow(userId);

    // ──────────────────────────── 픽스처 ────────────────────────────

    private static Workflow workflow(UUID owner) {
        Workflow w = Workflow.builder().userId(owner).name("w").isActive(true).build();
        ReflectionTestUtils.setField(w, "id", UUID.randomUUID());
        return w;
    }

    @SuppressWarnings("unchecked")
    private NodeTestSampleRepository fakeRepository() {
        NodeTestSampleRepository repo = mock(NodeTestSampleRepository.class);
        given(repo.findByWorkflowIdAndNodeId(any(), any())).willAnswer(i ->
            Optional.ofNullable(samples.get(i.getArgument(0) + ":" + i.getArgument(1))));
        given(repo.findByWorkflowIdAndNodeIdIn(any(), any())).willAnswer(i -> {
            Collection<String> ids = i.getArgument(1);
            return ids.stream().map(id -> samples.get(i.getArgument(0) + ":" + id))
                .filter(Objects::nonNull).toList();
        });
        given(repo.saveAndFlush(any())).willAnswer(i -> {
            NodeTestSample s = i.getArgument(0);
            samples.put(s.getWorkflow().getId() + ":" + s.getNodeId(), s);
            return s;
        });
        return repo;
    }

    private void seed(Workflow w, String nodeId, SampleStatus status, String outputJson) {
        NodeTestSample s = NodeTestSample.create(w, nodeId);
        s.record(status, outputJson, status == SampleStatus.FAILED ? "boom" : null, LocalDateTime.now());
        samples.put(w.getId() + ":" + nodeId, s);
    }

    private NodeTestSample stored(String nodeId) {
        return samples.get(workflow.getId() + ":" + nodeId);
    }

    private Node httpNode() {
        return httpNode(new HashMap<>(Map.of("method", "GET", "url", "https://example.com")));
    }

    private Node httpNode(Map<String, Object> config) {
        return new Node("node-1", NodeType.HTTP, "HTTP", config);
    }

    private Node transform(String id, String mappingValue) {
        return new Node(id, NodeType.TRANSFORM, id,
            new HashMap<>(Map.of("mappings", new HashMap<>(Map.of("v", mappingValue)))));
    }

    /** 호출을 기록하고 미리 정한 결과를 돌려주는 실행기. */
    private static final class FakeExecutor implements NodeExecutor {

        record Call(Node node, Map<String, Object> input, ExecutionCursor cursor, NodeAttempt attempt) {}

        private final NodeType type;
        final List<Call> calls = new ArrayList<>();
        ExecutorResult next = ExecutorResult.success(new HashMap<>(Map.of("ok", true)), 1);
        Exception toThrow;

        FakeExecutor(NodeType type) {
            this.type = type;
        }

        @Override
        public NodeType getNodeType() {
            return type;
        }

        @Override
        public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor)
            throws Exception {
            return execute(node, input, cursor, NodeAttempt.NONE);
        }

        @Override
        public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor,
                                      NodeAttempt attempt) throws Exception {
            calls.add(new Call(node, input, cursor, attempt));
            if (toThrow != null) {
                throw toThrow;
            }
            return next;
        }
    }

    // ──────────────────────────── 참조 샘플 → 실행 ────────────────────────────

    @Test
    @DisplayName("직접 참조한 앞 노드의 SUCCESS 샘플이 컨텍스트로 들어가 치환되고, 결과가 최신 샘플로 저장된다")
    void referencedSamplesFeedTheNode() {
        seed(workflow, "up", SampleStatus.SUCCESS, "{\"name\":\"철수\"}");

        NodeTestResult result = runner.run(workflow, transform("node-2", "안녕 {{nodes.up.output.name}}"), Map.of());

        assertThat(result.status()).isEqualTo(SampleStatus.SUCCESS);
        assertThat(result.output()).isEqualTo(Map.of("v", "안녕 철수"));
        assertThat(result.error()).isNull();
        assertThat(stored("node-2").getStatus()).isEqualTo(SampleStatus.SUCCESS);
        assertThat(stored("node-2").getOutputJson()).isEqualTo("{\"v\":\"안녕 철수\"}");
    }

    @Test
    @DisplayName("CONDITION도 같은 경로로 돌고 result 불리언이 샘플이 된다")
    void conditionNodeRuns() {
        seed(workflow, "up", SampleStatus.SUCCESS, "{\"status\":\"ok\"}");
        Node condition = new Node("node-c", NodeType.CONDITION, "c", new HashMap<>(Map.of(
            "operator", "equals", "left", "{{nodes.up.output.status}}", "right", "ok")));

        NodeTestResult result = runner.run(workflow, condition, Map.of());

        assertThat(result.output()).isEqualTo(Map.of("result", true));
    }

    @Test
    @DisplayName("참조한 노드 중 SUCCESS 샘플이 없으면 실행 없이 TestSampleMissingException — 없는 것 전부, 등장 순서")
    void missingSampleStopsBeforeRunning() {
        seed(workflow, "up1", SampleStatus.SUCCESS, "{}");
        Node node = httpNode(new HashMap<>(Map.of("url",
            "https://x/{{nodes.up1.output.a}}/{{nodes.up2.output.b}}/{{nodes.up3.output.c}}")));

        assertThatThrownBy(() -> runner.run(workflow, node, Map.of()))
            .isInstanceOfSatisfying(TestSampleMissingException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEST_SAMPLE_MISSING);
                assertThat(e.getMissingNodeIds()).containsExactly("up2", "up3");
            });
        assertThat(http.calls).isEmpty();
        assertThat(samples).doesNotContainKey(workflow.getId() + ":node-1");
    }

    @Test
    @DisplayName("다른 워크플로우의 같은 nodeId 샘플은 보이지 않는다")
    void samplesOfOtherWorkflowAreInvisible() {
        seed(workflow(UUID.randomUUID()), "up", SampleStatus.SUCCESS, "{\"a\":1}");

        assertThatThrownBy(() -> runner.run(workflow, transform("node-2", "{{nodes.up.output.a}}"), Map.of()))
            .isInstanceOf(TestSampleMissingException.class);
    }

    @Test
    @DisplayName("실행기는 소유자 userId·새 traceId·직접 참조한 샘플만 든 커서를 받는다 (SyncExecutionRuntime 없이)")
    void executorSeesOwnerTraceAndOnlyReferencedSamples() {
        seed(workflow, "up", SampleStatus.SUCCESS, "{\"name\":\"철수\"}");
        seed(workflow, "unrelated", SampleStatus.SUCCESS, "{\"x\":1}");
        Node node = httpNode(new HashMap<>(Map.of("url", "https://example.com/{{nodes.up.output.name}}")));

        runner.run(workflow, node, Map.of());

        FakeExecutor.Call call = http.calls.get(0);
        ExecutionCursor cursor = call.cursor();
        assertThat(cursor.getContext().getUserId()).isEqualTo(userId);
        assertThat(cursor.getContext().getTraceId()).matches("[0-9a-f]{32}");
        assertThat(cursor.getContext().getNodeOutputs()).containsOnlyKeys("up");
        assertThat(cursor.getAllNodes()).extracting(Node::getId).containsExactly("node-1");
        assertThat(cursor.getAllEdges()).isEmpty();
        assertThat(call.input()).containsEntry("url", "https://example.com/철수");
    }

    @Test
    @DisplayName("ACTION 노드 — 앞 노드 샘플의 리스트 원소를 {{nodes.list.output.issues.0.title}}로 참조한다 (BE-a의 인덱스 해석에 기댐)")
    void actionNodeReadsListIndexFromSample() {
        FakeExecutor action = new FakeExecutor(NodeType.ACTION);
        NodeTestRunner withAction = new NodeTestRunner(List.of(action), sampleRepository, crudService,
            new RetryProperties(), idempotencyStore, objectMapper);
        seed(workflow, "list", SampleStatus.SUCCESS, "{\"issues\":[{\"title\":\"첫 이슈\"},{\"title\":\"둘째\"}]}");
        Node create = new Node("node-1", NodeType.ACTION, "이슈 만들기", new HashMap<>(Map.of(
            "tools", List.of(Map.of("name", "builtin:github_create_issue",
                "config", Map.of("title", "{{nodes.list.output.issues.0.title}}"))))));

        withAction.run(workflow, create, Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> toolConfig = (Map<String, Object>) ((Map<String, Object>)
            ((List<Object>) action.calls.get(0).input().get("tools")).get(0)).get("config");
        assertThat(toolConfig).containsEntry("title", "첫 이슈");
    }

    // ──────────────────────────── 저장·마스킹·실패 ────────────────────────────

    @Test
    @DisplayName("실행 실패도 FAILED 샘플로 남는다 — 출력 없음, 메시지가 비면 기본 문구, 예외는 던지지 않는다")
    void failureIsSavedAsFailedSample() {
        http.next = ExecutorResult.failure(null, 3);

        NodeTestResult result = runner.run(workflow, httpNode(), Map.of());

        assertThat(result.status()).isEqualTo(SampleStatus.FAILED);
        assertThat(result.output()).isNull();
        assertThat(result.error()).isEqualTo("노드 실행에 실패했습니다.");
        assertThat(stored("node-1").getStatus()).isEqualTo(SampleStatus.FAILED);
        assertThat(stored("node-1").getOutputJson()).isNull();
        assertThat(stored("node-1").getErrorMessage()).isEqualTo("노드 실행에 실패했습니다.");
    }

    @Test
    @DisplayName("실행기가 예외를 던져도 FAILED 샘플이 된다")
    void executorExceptionBecomesFailedSample() {
        http.toThrow = new IOException("connection reset");

        NodeTestResult result = runner.run(workflow, httpNode(), Map.of());

        assertThat(result.status()).isEqualTo(SampleStatus.FAILED);
        assertThat(result.error()).contains("connection reset");
    }

    @Test
    @DisplayName("실패 메시지의 웹훅 URL은 응답·저장 모두 마스킹된다 — 런타임 node_runs·SSE와 같은 처리")
    void failureMessageWebhookUrlIsMasked() {
        http.next = ExecutorResult.failure("HTTP 404 for https://hooks.slack.com/services/T0/B0/xyz", 1);

        NodeTestResult result = runner.run(workflow, httpNode(), Map.of());

        assertThat(result.error()).doesNotContain("T0/B0/xyz").contains("hooks.slack.com/services/***");
        assertThat(stored("node-1").getErrorMessage())
            .doesNotContain("T0/B0/xyz").contains("hooks.slack.com/services/***");
    }

    @Test
    @DisplayName("같은 노드를 다시 테스트하면 행이 늘지 않고 최신 1건으로 덮인다")
    void upsertKeepsLatestOnly() {
        http.next = ExecutorResult.success(new HashMap<>(Map.of("n", 1)), 1);
        runner.run(workflow, httpNode(), Map.of());
        http.next = ExecutorResult.success(new HashMap<>(Map.of("n", 2)), 1);
        runner.run(workflow, httpNode(), Map.of());

        assertThat(samples).hasSize(1);
        assertThat(stored("node-1").getOutputJson()).isEqualTo("{\"n\":2}");
    }

    @Test
    @DisplayName("[Review Focus 3] 출력의 자격증명은 저장·응답에서 마스킹되고, 하류가 참조하면 ***가 들어간다")
    void maskedSampleFeedsDownstreamAsMask() {
        http.next = ExecutorResult.success(new HashMap<>(Map.of(
            "body", new HashMap<>(Map.of("apiKey", "sk-raw-123", "name", "a")),
            "token", "t-raw")), 1);

        NodeTestResult result = runner.run(workflow, httpNode(), Map.of());

        assertThat(stored("node-1").getOutputJson()).doesNotContain("sk-raw-123", "t-raw").contains("***");
        assertThat(result.output()).containsEntry("token", "***");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) result.output().get("body");
        assertThat(body).containsEntry("apiKey", "***").containsEntry("name", "a");

        // 원문은 어디에도 없다 — 하류 테스트가 그 필드를 참조하면 마스킹본이 그대로 들어간다(의도된 트레이드오프).
        NodeTestResult downstream = runner.run(workflow,
            transform("node-2", "{{nodes.node-1.output.token}}"), Map.of());
        assertThat(downstream.output()).containsEntry("v", "***");
    }

    @Test
    @DisplayName("[Review Focus 5] 성공 샘플이 있던 노드를 실패로 재테스트하면 FAILED가 덮어써 하류 테스트가 그 노드로 막힌다")
    void retestFailureOverwritesSuccessAndBlocksDownstream() {
        http.next = ExecutorResult.success(new HashMap<>(Map.of("id", 1)), 1);
        runner.run(workflow, httpNode(), Map.of());
        Node downstream = transform("node-2", "{{nodes.node-1.output.id}}");
        assertThat(runner.run(workflow, downstream, Map.of()).status()).isEqualTo(SampleStatus.SUCCESS);

        http.next = ExecutorResult.failure("HTTP 422: bad", 1);
        NodeTestResult failed = runner.run(workflow, httpNode(), Map.of());

        assertThat(failed.status()).isEqualTo(SampleStatus.FAILED);
        assertThat(stored("node-1").getStatus()).isEqualTo(SampleStatus.FAILED);
        assertThatThrownBy(() -> runner.run(workflow, downstream, Map.of()))
            .isInstanceOfSatisfying(TestSampleMissingException.class,
                e -> assertThat(e.getMissingNodeIds()).containsExactly("node-1"));
    }

    @Test
    @DisplayName("[Review Focus 4] 동시 첫 테스트로 UNIQUE에 지면 이긴 행을 갱신으로 다시 쓴다 — 예외 없이 최신 1건")
    void concurrentFirstTestsResolveAsUpdate() {
        AtomicInteger writes = new AtomicInteger();
        willAnswer(i -> {
            NodeTestSample mine = i.getArgument(0);
            if (writes.getAndIncrement() == 0) {
                // 다른 요청이 먼저 같은 (workflow, node) 행을 넣었다
                NodeTestSample winner = NodeTestSample.create(workflow, "node-1");
                winner.record(SampleStatus.SUCCESS, "{\"winner\":true}", null, LocalDateTime.now());
                samples.put(workflow.getId() + ":node-1", winner);
                throw new DataIntegrityViolationException("duplicate key");
            }
            samples.put(mine.getWorkflow().getId() + ":" + mine.getNodeId(), mine);
            return mine;
        }).given(sampleRepository).saveAndFlush(any());
        http.next = ExecutorResult.success(new HashMap<>(Map.of("mine", true)), 1);

        NodeTestResult result = runner.run(workflow, httpNode(), Map.of());

        assertThat(result.status()).isEqualTo(SampleStatus.SUCCESS);
        assertThat(writes).hasValue(2);
        assertThat(samples).hasSize(1);
        assertThat(stored("node-1").getOutputJson()).isEqualTo("{\"mine\":true}");
    }

    // ──────────────────────────── 멱등 키·마커·타입 ────────────────────────────

    @Test
    @DisplayName("멱등 키는 호출마다 새 test-<uuid>:<nodeId>, attempt 1, 정책 포함")
    void idempotencyKeyIsFreshPerTest() {
        runner.run(workflow, httpNode(), Map.of());
        runner.run(workflow, httpNode(), Map.of());

        NodeExecutor.NodeAttempt first = http.calls.get(0).attempt();
        NodeExecutor.NodeAttempt second = http.calls.get(1).attempt();
        assertThat(first.idempotencyKey()).matches(TEST_KEY);
        assertThat(second.idempotencyKey()).matches(TEST_KEY).isNotEqualTo(first.idempotencyKey());
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(first.policy()).isNotNull();
    }

    @Test
    @DisplayName("MARKER 모드 노드는 실행기가 던져도 테스트가 끝나면 마커를 해제한다")
    void markerIsClearedAfterTest() {
        Node node = httpNode(new HashMap<>(Map.of("url", "https://example.com",
            "retry", Map.of("idempotency", "MARKER"))));
        http.toThrow = new IllegalStateException("boom");

        NodeTestResult result = runner.run(workflow, node, Map.of());

        assertThat(result.status()).isEqualTo(SampleStatus.FAILED);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(idempotencyStore).clearInFlight(key.capture());
        assertThat(key.getValue()).isEqualTo(http.calls.get(0).attempt().idempotencyKey());
    }

    @Test
    @DisplayName("MARKER가 아니면 마커를 건드리지 않는다")
    void markerUntouchedByDefault() {
        runner.run(workflow, httpNode(), Map.of());

        verify(idempotencyStore, never()).clearInFlight(any());
    }

    @Test
    @DisplayName("APPROVAL은 실행 없이 런타임 승인 출력 모양의 SUCCESS 샘플을 남기고, 하류가 그 출력을 참조해 테스트된다")
    void approvalSampleFeedsDownstream() {
        Node gate = new Node("gate", NodeType.APPROVAL, "승인", new HashMap<>());

        NodeTestResult gateResult = runner.run(workflow, gate, Map.of());

        assertThat(gateResult.status()).isEqualTo(SampleStatus.SUCCESS);
        assertThat(gateResult.output()).containsEntry("approved", true)
            .containsEntry("approvedBy", userId.toString()).containsKey("approvedAt");
        NodeTestResult downstream =
            runner.run(workflow, transform("node-2", "승인자 {{nodes.gate.output.approvedBy}}"), Map.of());
        assertThat(downstream.output()).isEqualTo(Map.of("v", "승인자 " + userId));
    }

    @Test
    @DisplayName("등록된 실행기가 없는 타입도 400 INVALID_INPUT")
    void typeWithoutExecutorIsRejected() {
        NodeTestRunner onlyAi = new NodeTestRunner(List.of(ai), sampleRepository, crudService,
            new RetryProperties(), idempotencyStore, objectMapper);

        assertThatThrownBy(() -> onlyAi.run(workflow, httpNode(), Map.of()))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    // ──────────────────────────── 샘플·저장 노드 조회 ────────────────────────────

    @Test
    @DisplayName("findSample — 저장된 샘플을 파싱해 돌려주고, 없으면 empty")
    void findSampleParsesStoredOutput() {
        seed(workflow, "node-1", SampleStatus.SUCCESS, "{\"issues\":[{\"title\":\"a\"}]}");
        seed(workflow, "node-2", SampleStatus.FAILED, null);

        NodeTestResult ok = runner.findSample(workflow.getId(), "node-1").orElseThrow();
        NodeTestResult failed = runner.findSample(workflow.getId(), "node-2").orElseThrow();

        assertThat(ok.status()).isEqualTo(SampleStatus.SUCCESS);
        assertThat(ok.output()).containsKey("issues");
        assertThat(failed.status()).isEqualTo(SampleStatus.FAILED);
        assertThat(failed.output()).isNull();
        assertThat(failed.error()).isEqualTo("boom");
        assertThat(runner.findSample(workflow.getId(), "none")).isEmpty();
    }

    @Test
    @DisplayName("findSavedNode — 최신 버전 정의에서 id로 노드를 읽는다(모르는 필드 무시), 없으면 empty")
    void findSavedNodeReadsLatestDefinition() {
        WorkflowVersion version = mock(WorkflowVersion.class);
        given(crudService.findLatestVersion(workflow.getId())).willReturn(Optional.of(version));
        given(crudService.loadDefinition(version)).willReturn(WorkflowDefinitionDocument.builder()
            .nodes(List.of(
                Map.of("id", "a", "type", "HTTP", "label", "A", "description", "설명",
                    "position", Map.of("x", 1, "y", 2), "config", Map.of("method", "GET")),
                Map.of("id", "b", "type", "TRANSFORM", "label", "B", "config", Map.of())))
            .edges(List.of()).build());

        Node found = runner.findSavedNode(workflow.getId(), "a").orElseThrow();

        assertThat(found.getType()).isEqualTo(NodeType.HTTP);
        assertThat(found.getConfig()).isEqualTo(Map.of("method", "GET"));
        assertThat(runner.findSavedNode(workflow.getId(), "zzz")).isEmpty();
    }

    @Test
    @DisplayName("findSavedNode — 저장된 버전이 없으면 empty")
    void findSavedNodeWithoutVersion() {
        given(crudService.findLatestVersion(workflow.getId())).willReturn(Optional.empty());

        assertThat(runner.findSavedNode(workflow.getId(), "a")).isEmpty();
    }

    // ──────────────────────────── 트리거 (spec §5.4) ────────────────────────────

    private Node trigger(Map<String, Object> config) {
        return new Node("t", NodeType.TRIGGER, "트리거", new HashMap<>(config));
    }

    @Test
    @DisplayName("manual — 샘플은 원 페이로드가 아니라 치환된 트리거 config다: 자기 참조 없으면 페이로드 필드가 안 남는다(런타임과 같다)")
    void manualSampleIsRenderedConfigNotRawPayload() {
        NodeTestResult result = runner.run(workflow, trigger(Map.of("triggerType", "MANUAL")),
            Map.of("score", 5));

        assertThat(result.status()).isEqualTo(SampleStatus.SUCCESS);
        assertThat(result.output()).isEqualTo(Map.of("triggerType", "MANUAL"));
    }

    @Test
    @DisplayName("manual — config가 {{nodes.t.output.X}}로 자기 참조하면 페이로드 필드가 문자열로 남고, 민감 키는 마스킹된다")
    void manualSelfReferenceBringsPayloadFields() {
        NodeTestResult result = runner.run(workflow, trigger(Map.of(
            "triggerType", "MANUAL",
            "score", "{{nodes.t.output.score}}",
            "token", "{{nodes.t.output.token}}")),
            Map.of("score", 85, "token", "sk-raw"));

        assertThat(result.output()).containsEntry("triggerType", "MANUAL")
            .containsEntry("score", "85").containsEntry("token", "***");
        assertThat(stored("t").getOutputJson()).doesNotContain("sk-raw");
    }

    @Test
    @DisplayName("input이 null이어도 빈 페이로드로 성공한다")
    void nullInputIsEmptyPayload() {
        NodeTestResult result = runner.run(workflow, trigger(Map.of("triggerType", "MANUAL")), null);

        assertThat(result.status()).isEqualTo(SampleStatus.SUCCESS);
    }

    @Test
    @DisplayName("schedule — {triggeredAt, cron}을 내고 요청 input은 무시한다")
    void scheduleEmitsTriggeredAtAndCron() {
        NodeTestResult result = runner.run(workflow,
            trigger(Map.of("triggerType", "SCHEDULE", "cron", "0 0 9 * * ?")), Map.of("x", 1));

        assertThat(result.output()).containsKeys("triggeredAt", "cron").doesNotContainKey("x");
        assertThat(result.output()).containsEntry("cron", "0 0 9 * * ?");
    }

    @Test
    @DisplayName("schedule에 cron이 없으면 500이 아니라 FAILED 샘플")
    void scheduleWithoutCronIsFailedSample() {
        NodeTestResult result = runner.run(workflow, trigger(Map.of("triggerType", "SCHEDULE")), Map.of());

        assertThat(result.status()).isEqualTo(SampleStatus.FAILED);
        assertThat(result.error()).contains("cron");
        assertThat(stored("t").getStatus()).isEqualTo(SampleStatus.FAILED);
    }

    @Test
    @DisplayName("트리거는 앞 노드 샘플을 요구하지 않는다 — 다른 노드 참조는 런타임처럼 빈 문자열")
    void triggerDoesNotRequireOtherSamples() {
        NodeTestResult result = runner.run(workflow, trigger(Map.of(
            "triggerType", "MANUAL", "x", "{{nodes.other.output.x}}")), Map.of());

        assertThat(result.status()).isEqualTo(SampleStatus.SUCCESS);
        assertThat(result.output()).containsEntry("x", "");
    }

    // ──────────────────────────── webhook 수신 샘플 ────────────────────────────

    private void saveVersionWithNodes(List<Map<String, Object>> nodes) {
        WorkflowVersion version = mock(WorkflowVersion.class);
        given(crudService.findLatestVersion(workflow.getId())).willReturn(Optional.of(version));
        given(crudService.loadDefinition(version)).willReturn(
            WorkflowDefinitionDocument.builder().nodes(nodes).edges(List.of()).build());
    }

    @Test
    @DisplayName("webhook 수신 — 저장된 트리거 config를 쓰고 페이로드는 자기 참조로만 남는다")
    void webhookSampleUsesSavedTriggerConfig() {
        saveVersionWithNodes(List.of(Map.of("id", "t", "type", "TRIGGER", "label", "수신", "config",
            Map.of("triggerType", "WEBHOOK", "orderId", "{{nodes.t.output.orderId}}"))));

        NodeTestResult result = runner.recordWebhookSample(workflow, "t", Map.of("orderId", "ORD-1", "memo", "x"));

        assertThat(result.status()).isEqualTo(SampleStatus.SUCCESS);
        assertThat(result.output()).isEqualTo(Map.of("triggerType", "WEBHOOK", "orderId", "ORD-1"));
        assertThat(stored("t").getOutputJson()).contains("ORD-1");
    }

    @Test
    @DisplayName("저장 전 노드(버전 없음·버전에 노드 없음·TRIGGER 아님)면 최소 WEBHOOK 트리거로 샘플을 만든다")
    void webhookSampleFallsBackToMinimalTrigger() {
        given(crudService.findLatestVersion(workflow.getId())).willReturn(Optional.empty());
        assertThat(runner.recordWebhookSample(workflow, "t", Map.of("a", 1)).output())
            .isEqualTo(Map.of("triggerType", "WEBHOOK"));

        saveVersionWithNodes(List.of(
            Map.of("id", "other", "type", "TRIGGER", "label", "x", "config", Map.of()),
            Map.of("id", "t", "type", "HTTP", "label", "충돌", "config", Map.of("method", "GET"))));
        assertThat(runner.recordWebhookSample(workflow, "t", Map.of()).output())
            .isEqualTo(Map.of("triggerType", "WEBHOOK"));
    }

    @Test
    @DisplayName("webhook 샘플의 민감 페이로드는 저장 전에 마스킹된다")
    void webhookSampleIsMasked() {
        saveVersionWithNodes(List.of(Map.of("id", "t", "type", "TRIGGER", "label", "수신", "config",
            Map.of("triggerType", "WEBHOOK", "password", "{{nodes.t.output.password}}"))));

        runner.recordWebhookSample(workflow, "t", Map.of("password", "pw-raw"));

        assertThat(stored("t").getOutputJson()).doesNotContain("pw-raw");
    }
}
