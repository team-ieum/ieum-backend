package com.ieum.workflowcore.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.config.RetryProperties;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.NodeTestSample;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.SampleStatus;
import com.ieum.workflowcore.engine.ExecutionContext;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.FailureClassifier;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.engine.RetryPolicy;
import com.ieum.workflowcore.engine.executor.IdempotencyStore;
import com.ieum.workflowcore.engine.executor.NodeExecutor;
import com.ieum.workflowcore.repository.NodeTestSampleRepository;
import com.ieum.workflowcore.util.SensitiveDataMasker;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 노드 하나를 {@code SyncExecutionRuntime} 밖에서 실행하고 결과를 최신 샘플 1건으로 남긴다.
 *
 * <p>실행기는 타입으로 골라 직접 부른다 — 실행기가 읽는 커서 상태는 userId·traceId·nodeOutputs와
 * 치환 메서드뿐이라 테스트용 컨텍스트로 충분하다(그래프 헬퍼·executionId는 쓰지 않는다). 쿼터 예약·토큰
 * 차감은 AI 실행기 안에 있어 그대로 적용되고, <b>쓰기 액션은 실제로 실행된다</b>.
 *
 * <p>단일 시도다 — 재시도·모델 fallback은 쓰지 않는다(사용자가 첫 실패를 본다). 멱등 키는 호출마다 새
 * {@code test-<uuid>:<nodeId>}라 agent 캐시가 이전 테스트 응답을 재생하지 않는다.
 *
 * <p>트랜잭션을 걸지 않는다 — 외부·에이전트 호출 동안 DB 커넥션을 쥐지 않으려는 의도다.
 * 저장소 호출은 각자 트랜잭션이다.
 */
@Slf4j
@Component
public class NodeTestRunner {

    private static final String DEFAULT_FAILURE_MESSAGE = "노드 실행에 실패했습니다.";

    private final Map<NodeType, NodeExecutor> executors = new EnumMap<>(NodeType.class);
    private final NodeTestSampleRepository sampleRepository;
    private final WorkflowCrudService workflowCrudService;
    private final RetryProperties retryProperties;
    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;

    public NodeTestRunner(List<NodeExecutor> nodeExecutors, NodeTestSampleRepository sampleRepository,
                          WorkflowCrudService workflowCrudService, RetryProperties retryProperties,
                          IdempotencyStore idempotencyStore, ObjectMapper objectMapper) {
        nodeExecutors.forEach(e -> executors.put(e.getNodeType(), e));
        this.sampleRepository = sampleRepository;
        this.workflowCrudService = workflowCrudService;
        this.retryProperties = retryProperties;
        this.idempotencyStore = idempotencyStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 노드를 실행하고 샘플로 저장한다.
     *
     * @param workflow 소유자 검사가 끝난 워크플로우(컨텍스트 userId는 그 소유자)
     * @param node     테스트할 노드(편집 중 정의 또는 저장된 노드)
     * @param input    MANUAL 트리거의 페이로드. 그 외 노드는 쓰지 않는다
     * @throws TestSampleMissingException 직접 참조한 노드 중 SUCCESS 샘플이 없는 것이 있을 때
     * @throws CustomException            APPROVAL이거나 실행기가 없는 타입일 때(INVALID_INPUT)
     */
    public NodeTestResult run(Workflow workflow, Node node, Map<String, Object> input) {
        NodeExecutor executor = executorFor(node);
        ExecutionCursor cursor = newCursor(workflow, node);
        loadReferencedSamples(workflow, node, cursor.getContext());
        return saveSample(workflow, node.getId(), execute(executor, node, renderedConfig(node, cursor), cursor));
    }

    /** 저장된 최신 샘플. 없으면 empty. */
    public Optional<NodeTestResult> findSample(UUID workflowId, String nodeId) {
        return sampleRepository.findByWorkflowIdAndNodeId(workflowId, nodeId).map(this::toResult);
    }

    /** 저장된 최신 버전 정의에서 노드를 읽는다. 버전이나 노드가 없으면 empty. */
    public Optional<Node> findSavedNode(UUID workflowId, String nodeId) {
        return workflowCrudService.findLatestVersion(workflowId)
            .map(workflowCrudService::loadDefinition)
            .map(WorkflowDefinitionDocument::getNodes)
            .flatMap(nodes -> nodes.stream().filter(n -> nodeId.equals(n.get("id"))).findFirst())
            .map(raw -> objectMapper.convertValue(raw, Node.class));
    }

    // ──────────────────────────── 실행 ────────────────────────────

    private NodeExecutor executorFor(Node node) {
        NodeExecutor executor = node.getType() == NodeType.APPROVAL ? null : executors.get(node.getType());
        if (executor == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "테스트할 수 없는 노드 타입입니다: " + node.getType());
        }
        return executor;
    }

    /** 테스트용 컨텍스트 — 실행기는 그래프를 읽지 않지만 {@code findNode}가 자기 자신은 찾도록 노드 하나를 넣어 둔다. */
    private ExecutionCursor newCursor(Workflow workflow, Node node) {
        ExecutionContext context = new ExecutionContext();
        context.setUserId(workflow.getUserId());
        context.setTraceId(UUID.randomUUID().toString().replace("-", ""));
        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setAllNodes(List.of(node));
        cursor.setAllEdges(List.of());
        cursor.setContext(context);
        return cursor;
    }

    /** 직접 참조한 노드의 SUCCESS 샘플만 컨텍스트에 넣는다. 하나라도 없으면 실행하지 않는다. */
    private void loadReferencedSamples(Workflow workflow, Node node, ExecutionContext context) {
        Set<String> referenced = ExecutionCursor.referencedNodeIds(node.getConfig());
        if (referenced.isEmpty()) {
            return;
        }
        Map<String, NodeTestSample> successes = sampleRepository
            .findByWorkflowIdAndNodeIdIn(workflow.getId(), referenced).stream()
            .filter(s -> s.getStatus() == SampleStatus.SUCCESS)
            .collect(Collectors.toMap(NodeTestSample::getNodeId, Function.identity()));
        List<String> missing = referenced.stream().filter(id -> !successes.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new TestSampleMissingException(missing);
        }
        successes.forEach((id, sample) -> context.setNodeOutput(id, readOutput(sample.getOutputJson())));
    }

    /** 런타임의 {@code prepareNodeInput}과 같다 — 노드 원본은 건드리지 않고 치환한 복사본을 만든다. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> renderedConfig(Node node, ExecutionCursor cursor) {
        return node.getConfig() == null
            ? new HashMap<>() : (Map<String, Object>) cursor.renderDeep(node.getConfig());
    }

    private ExecutorResult execute(NodeExecutor executor, Node node, Map<String, Object> input,
                                   ExecutionCursor cursor) {
        RetryPolicy policy = RetryPolicy.from(node.getConfig(), node.getType(), retryProperties);
        String key = "test-" + UUID.randomUUID() + ":" + node.getId();
        long start = System.currentTimeMillis();
        try {
            return executor.execute(node, input, cursor, new NodeExecutor.NodeAttempt(1, key, policy));
        } catch (Exception e) {
            return ExecutorResult.failure(e.toString(), System.currentTimeMillis() - start,
                FailureClassifier.fromException(e));
        } finally {
            // 마커는 런타임이 재시도 루프 뒤에 해제하던 것 — 러너가 대신한다.
            if (policy.idempotency().usesMarker()) {
                idempotencyStore.clearInFlight(key);
            }
        }
    }

    // ──────────────────────────── 샘플 저장 ────────────────────────────

    private NodeTestResult saveSample(Workflow workflow, String nodeId, ExecutorResult result) {
        SampleStatus status = result.isSuccess() ? SampleStatus.SUCCESS : SampleStatus.FAILED;
        Map<String, Object> output = null;
        String error = null;
        if (result.isSuccess()) {
            Map<String, Object> masked = SensitiveDataMasker.mask(result.getOutput());
            output = masked != null ? masked : new LinkedHashMap<>();
        } else {
            // 런타임이 node_runs·SSE에 하듯 오류 본문의 웹훅 URL 비밀 구간을 가린다.
            String message = result.getErrorMessage();
            error = SensitiveDataMasker.maskWebhookUrl(
                message == null || message.isBlank() ? DEFAULT_FAILURE_MESSAGE : message);
        }
        LocalDateTime testedAt = LocalDateTime.now();
        upsert(workflow, nodeId, status, output == null ? null : toJson(output), error, testedAt);
        return new NodeTestResult(status, output, error, testedAt);
    }

    private void upsert(Workflow workflow, String nodeId, SampleStatus status, String outputJson,
                        String error, LocalDateTime testedAt) {
        try {
            write(workflow, nodeId, status, outputJson, error, testedAt);
        } catch (DataIntegrityViolationException e) {
            // 같은 노드를 동시에 처음 테스트하다 UNIQUE(workflow_id, node_id)에 진 쪽이다.
            // 이긴 쪽 행이 이제 있으니 이번엔 갱신으로 한 번 더 쓴다.
            write(workflow, nodeId, status, outputJson, error, testedAt);
        }
    }

    private void write(Workflow workflow, String nodeId, SampleStatus status, String outputJson,
                       String error, LocalDateTime testedAt) {
        NodeTestSample sample = sampleRepository.findByWorkflowIdAndNodeId(workflow.getId(), nodeId)
            .orElseGet(() -> NodeTestSample.create(workflow, nodeId));
        sample.record(status, outputJson, error, testedAt);
        sampleRepository.saveAndFlush(sample);
    }

    private NodeTestResult toResult(NodeTestSample sample) {
        return new NodeTestResult(sample.getStatus(),
            sample.getOutputJson() == null ? null : readOutput(sample.getOutputJson()),
            sample.getErrorMessage(), sample.getTestedAt());
    }

    private Map<String, Object> readOutput(String json) {
        if (json == null) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "저장된 샘플을 읽을 수 없습니다.");
        }
    }

    private String toJson(Map<String, Object> output) {
        try {
            return objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "샘플을 저장할 수 없습니다.");
        }
    }
}
