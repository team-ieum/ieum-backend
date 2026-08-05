package com.ieum.workflowcore.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.config.RetryProperties;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowExecutionLog;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.domain.enums.ExecutionLogStatus;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.event.ExecutionEvent;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.engine.executor.AlertNotifier;
import com.ieum.workflowcore.engine.executor.IdempotencyStore;
import com.ieum.workflowcore.engine.executor.NodeExecutor;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.util.SensitiveDataMasker;
import jakarta.annotation.PostConstruct;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 워크플로우를 동기식으로 순차 실행하는 런타임.
 *
 * <p>TRIGGER 노드부터 시작해 {@link ExecutionCursor}가 반환하는 다음 노드를 따라
 * 마지막 노드까지 순회하며 각 노드를 {@link NodeExecutor}에 위임한다.
 * 노드별 실행 결과는 {@link WorkflowExecutionLog}로 저장된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncExecutionRuntime {

    private final ObjectMapper objectMapper;
    private final WorkflowExecutionLogRepository executionLogRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final WorkflowCrudService workflowCrudService;
    private final ExecutionEventPublisher eventPublisher;
    /** @Component로 등록된 모든 NodeExecutor 구현체를 Spring이 자동 주입 */
    private final List<NodeExecutor> nodeExecutors;
    private final RetryProperties retryProperties;
    private final IdempotencyStore idempotencyStore;
    private final AlertNotifier alertNotifier;

    /** 노드 타입 → 실행 전략. @PostConstruct에서 nodeExecutors로부터 구성된다. */
    private final Map<NodeType, NodeExecutor> executorMap = new EnumMap<>(NodeType.class);

    /**
     * 재시도 백오프 지터에 쓰는 난수 생성기. 워커 스레드들이 공유하는 필드지만
     * {@link ThreadLocalRandom}의 상태는 호출 스레드의 Thread 객체에 저장되므로 스레드 안전하다.
     * {@code RandomGenerator.getDefault()}(L32X64MixRandom 등)는 스레드 안전하지 않아 쓰지 않는다.
     */
    private final RandomGenerator random = ThreadLocalRandom.current();

    /** fan-out 병렬 실행 시 동시에 실행할 노드 수(워커 스레드 풀 크기). */
    @Value("${workflow.execution.parallelism:4}")
    private int parallelism;

    /**
     * 워커 스레드가 메인으로 돌려주는 노드 실행 결과 묶음. attempts는 마지막으로 실행된 시도 회차.
     * retryExhausted는 재시도 대상 실패로 maxAttempts까지 다 써버리고도 실패했는지.
     * skipped는 executor를 부르지 않고 주입된 출력으로 대체했는지(재처리).
     */
    private record NodeOutcome(Node node, Map<String, Object> input,
                               ExecutorResult result, long durationMs, int attempts,
                               boolean retryExhausted, boolean skipped) {}

    @PostConstruct
    void initExecutorMap() {
        nodeExecutors.forEach(e -> executorMap.put(e.getNodeType(), e));
        log.info("[Runtime] 등록된 NodeExecutor: {}", executorMap.keySet());
    }

    /**
     * 워크플로우 버전을 파싱하여 동기적으로 실행한다.
     *
     * @param workflowVersion 실행할 버전 (mongoDefinitionId로 MongoDB에서 nodes/edges 로드)
     * @param executionId     DB에 저장된 실행 인스턴스 ID (fresh 조회로 detached 문제 방지)
     * @param triggerData     트리거가 전달한 초기 데이터 (TRIGGER 노드 output에 저장)
     */
    public void execute(
        WorkflowVersion workflowVersion,
        UUID executionId,
        Map<String, Object> triggerData
    ) throws Exception {
        execute(workflowVersion, executionId, triggerData, Map.of());
    }

    /**
     * 일부 노드의 출력을 미리 받아 그 노드를 실행하지 않고 건너뛰며 실행한다(실패 실행 재처리).
     *
     * <p>{@code preCompletedOutputs}가 비어 있으면 위 3-인자 실행과 완전히 동일하게 동작한다 —
     * 일반 실행 경로는 이 매개변수를 타지 않는다.
     *
     * @param preCompletedOutputs nodeId → 그 노드의 출력. 여기 있는 노드는 {@link NodeExecutor}를
     *                            호출하지 않고 주어진 출력을 그대로 성공 결과로 삼아 컨텍스트에 넣고,
     *                            {@link ExecutionLogStatus#SKIPPED}로 로그를 남긴다.
     *                            <b>TRIGGER 노드는 예외로 항상 다시 실행한다</b> — 아래 참조.
     */
    public void execute(
        WorkflowVersion workflowVersion,
        UUID executionId,
        Map<String, Object> triggerData,
        Map<String, Map<String, Object>> preCompletedOutputs
    ) throws Exception {

        Map<String, Map<String, Object>> preCompleted =
            preCompletedOutputs != null ? preCompletedOutputs : Map.<String, Map<String, Object>>of();

        WorkflowExecution execution = workflowExecutionRepository.findWithWorkflowById(executionId)
            .orElseThrow(() -> new CustomException(ErrorCode.EXECUTION_NOT_FOUND));

        // 이벤트에 실을 workflowId. findWithWorkflowById가 workflow를 fetch join하므로 여기서 읽을 수 있다.
        // 지역 변수로만 들고 다니고 ExecutionCursor/ExecutionContext에는 넣지 않는다 —
        // 그 둘은 워커 스레드가 공유하는 객체다.
        UUID workflowId = execution.getWorkflow().getId();

        log.info("[Runtime] 워크플로우 실행 시작 — executionId: {}, versionId: {}",
            execution.getId(), workflowVersion.getId());

        // 1. MongoDB에서 노드/엣지 로드 및 TRIGGER 검증 (start() 이전 — 실패 시 FAILED로 전환)
        List<Node> nodes;
        List<Edge> edges;
        Node triggerNode;
        try {
            WorkflowDefinitionDocument definition = workflowCrudService.loadDefinition(workflowVersion);
            nodes = objectMapper.convertValue(definition.getNodes(), new TypeReference<>() {});
            edges = objectMapper.convertValue(definition.getEdges(), new TypeReference<>() {});
            triggerNode = nodes.stream()
                .filter(n -> n.getType() == NodeType.TRIGGER)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("TRIGGER 노드가 없습니다."));
        } catch (Exception e) {
            log.error("[Runtime] 워크플로우 초기화 실패 — executionId: {}, error: {}",
                execution.getId(), e.getMessage());
            finalizeFailure(execution, executionId, false, null, e.getMessage());
            eventPublisher.complete(executionId);
            throw e;
        }

        try {
            // 2. ExecutionCursor 초기화
            ExecutionCursor cursor = new ExecutionCursor();
            cursor.setAllNodes(nodes);
            cursor.setAllEdges(edges);
            ExecutionContext context = new ExecutionContext();
            context.setUserId(execution.getWorkflow().getUserId());
            context.setTraceId(execution.getTraceId());
            cursor.setContext(context);

            // 트리거 입력 데이터 보존(트리거 노드 실행 입력으로 사용)
            cursor.getContext().setNodeOutput(triggerNode.getId(),
                triggerData != null ? triggerData : new HashMap<>());

            // 재처리라도 TRIGGER 노드는 주입하지 않고 다시 실행한다. node_runs에 남은 output은
            // SensitiveDataMasker를 거친 값이라 트리거에 실린 민감값이 ***로 흘러가는데,
            // TriggerNodeExecutor는 부작용이 없어 재실행이 공짜이고 복호된 triggerData로
            // 정상 실행과 똑같은 출력을 스스로 다시 만든다.
            if (preCompleted.containsKey(triggerNode.getId())) {
                preCompleted = new HashMap<>(preCompleted);
                preCompleted.remove(triggerNode.getId());
            }

            // 2-1. 구조적 순환 검사(위상정렬 불가 = 사이클)
            assertNoCycle(nodes, edges);

            // 2-2. 모든 노드 Executor 존재 사전 검증 (디스패치 도중 throw로 인한 고아 태스크 방지)
            for (Node n : nodes) {
                if (!executorMap.containsKey(n.getType())) {
                    throw new IllegalStateException("NodeExecutor 없음 — type: " + n.getType());
                }
            }

            // 3. RUNNING 상태로 전환
            execution.start();
            workflowExecutionRepository.save(execution);

            // 4. 위상 스케줄러 상태: pending=미해소 incoming 수, hasLive=live 입력 보유 여부
            Map<String, Integer> pending = new HashMap<>();
            Map<String, Boolean> hasLive = new HashMap<>();
            for (Node n : nodes) {
                pending.put(n.getId(), cursor.incomingEdges(n.getId()).size());
                hasLive.put(n.getId(), false);
            }
            Set<String> resolved = new HashSet<>();   // 실행 완료 ∪ skip

            // 5. fan-out 병렬 실행 (워커=노드실행, 메인=상태/JPA 독점)
            // ExecutorService는 try-with-resources로 관리 — 블록 종료 시 close()가 자동 shutdown+종료 대기.
            boolean failed = false;
            boolean failedNodeRetryExhausted = false;
            String failedNodeId = null;
            String failedNodeError = null;
            try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallelism))) {
                CompletionService<NodeOutcome> completion = new ExecutorCompletionService<>(pool);
                int inFlight = 0;
                try {
                    Deque<Node> ready = new ArrayDeque<>();
                    ready.add(triggerNode);   // 트리거는 incoming 0 → 최초 ready
                    inFlight += dispatch(ready, cursor, completion, executionId, workflowId,
                        preCompleted);

                    while (inFlight > 0) {
                        NodeOutcome outcome = completion.take().get();
                        inFlight--;
                        Node node = outcome.node();
                        ExecutorResult result = outcome.result();
                        long durMs = outcome.durationMs();
                        log.info("[Runtime] 노드 완료 — nodeId: {}, success: {}, durationMs: {}",
                            node.getId(), result.isSuccess(), durMs);

                        // 5-1. 실행 로그 저장(메인 스레드)
                        saveExecutionLog(execution, node, outcome.input(), result, durMs,
                            outcome.attempts(), outcome.skipped());

                        // 5-2. 실패 시 전체 중단(신규 디스패치 멈춤 → finally에서 in-flight 드레인)
                        if (!result.isSuccess()) {
                            log.error("[Runtime] 노드 실패로 워크플로우 중단 — nodeId: {}, error: {}",
                                node.getId(), result.getErrorMessage());
                            eventPublisher.publish(executionId, ExecutionEvent.nodeFailed(
                                executionId, workflowId, node.getId(), node.getType(),
                                result.getErrorMessage(), durMs));
                            failed = true;
                            failedNodeRetryExhausted = outcome.retryExhausted();
                            failedNodeId = node.getId();
                            failedNodeError = result.getErrorMessage();
                            break;
                        }

                        // 5-3. 성공 처리: 이벤트 + 컨텍스트 저장
                        eventPublisher.publish(executionId, ExecutionEvent.nodeCompleted(
                            executionId, workflowId, node.getId(), node.getType(), durMs));
                        cursor.updateContext(node.getId(), result.getOutput());
                        resolved.add(node.getId());

                        // 5-4. 엣지 전파 → 새로 준비된(모든 입력 해소+live) 노드 디스패치
                        Deque<Node> newReady = new ArrayDeque<>();
                        propagate(node, cursor, pending, hasLive, resolved, newReady);
                        inFlight += dispatch(newReady, cursor, completion, executionId, workflowId,
                            preCompleted);
                    }
                } finally {
                    // 실패/예외 시 남은 in-flight 작업 드레인(완료 대기) 후 close()로 풀 종료.
                    // 드레인된 노드는 이미 실행돼 부수효과가 발생했으므로 성공/실패 무관하게 로그를 남긴다(감사).
                    // (외부 AI/HTTP 호출은 강제 취소가 불가하므로 인터럽트 대신 드레인)
                    while (inFlight > 0) {
                        try {
                            NodeOutcome drained = completion.take().get();
                            saveExecutionLog(execution, drained.node(), drained.input(),
                                drained.result(), drained.durationMs(), drained.attempts(),
                                drained.skipped());
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Exception ex) {
                            log.debug("[Runtime] 드레인 중 워커 결과 회수 실패 — 무시", ex);
                        }
                        inFlight--;
                    }
                }
            }

            // 6. 종료 처리
            if (failed) {
                finalizeFailure(execution, executionId, failedNodeRetryExhausted,
                    failedNodeId, failedNodeError);
                return;
            }

            execution.complete();
            workflowExecutionRepository.save(execution);
            log.info("[Runtime] 워크플로우 성공 — executionId: {}", execution.getId());
            eventPublisher.publish(executionId, ExecutionEvent.executionCompleted(
                executionId, workflowId, ExecutionStatus.SUCCESS));

        } catch (Exception e) {
            log.error("[Runtime] 워크플로우 실행 중 예외 — executionId: {}", execution.getId(), e);
            // 실패 노드를 특정할 수 없는 경로다(위상 검증 실패, Cursor 초기화 실패 등).
            // 이미 종료된 실행이면 finalizeFailure가 상태·알림을 건드리지 않고 종료 이벤트만 흘린다.
            finalizeFailure(execution, executionId, false, null, e.getMessage());
            throw e;
        } finally {
            // 성공·노드 실패(return)·예외 등 모든 종료 경로에서 SSE 스트림을 닫는다.
            eventPublisher.complete(executionId);
        }
    }

    /**
     * 실패 확정 단일 지점 — 상태 전이 + SSE 종료 이벤트 + 실패 알림을 한 곳에 모은다.
     *
     * <p>이미 종료(SUCCESS/FAILED)된 실행은 상태를 되돌리지도, 알림을 다시 보내지도 않는다.
     * 종료 이벤트만 전이 여부와 무관하게 흘린다 — 늦게 붙은 SSE 구독자가 스트림 종료를 알아야 한다.
     *
     * <p>알림은 상태 전이가 실제로 일어난 경우에만 발신한다. 이 규칙 덕에
     * {@code WorkflowExecutionService.markAsFailed()}가 뒤이어 불려도 중복 발신이 되지 않는다.
     *
     * @param failedNodeId 실패한 노드 ID. 노드를 특정할 수 없는 경로(정의 로드·위상 검증 실패)에선 null
     * @param errorSummary 오류 요약. 알림 문구에 실린다 — 프롬프트 원문·자격증명이 아닌 값만 넘길 것
     */
    private void finalizeFailure(WorkflowExecution execution, UUID executionId,
                                 boolean retryExhausted, String failedNodeId, String errorSummary) {
        boolean transitioned = execution.getStatus() != ExecutionStatus.SUCCESS
            && execution.getStatus() != ExecutionStatus.FAILED;
        if (transitioned) {
            execution.fail(retryExhausted);
            workflowExecutionRepository.save(execution);
        }
        eventPublisher.publish(executionId, ExecutionEvent.executionCompleted(
            executionId, execution.getWorkflow().getId(), ExecutionStatus.FAILED));
        if (transitioned) {
            notifyFailure(execution, retryExhausted, failedNodeId, errorSummary);
        }
    }

    /**
     * 실패 알림을 발신한다. 발신 실패는 warn만 남기고 삼킨다 —
     * 알림이 실행 종료 처리를 깨면 안 된다(실행 로그 저장 실패와 같은 정책).
     */
    private void notifyFailure(WorkflowExecution execution, boolean retryExhausted,
                               String failedNodeId, String errorSummary) {
        try {
            alertNotifier.notifyExecutionFailed(new AlertNotifier.ExecutionFailureAlert(
                execution.getId(),
                execution.getWorkflow().getId(),
                execution.getWorkflow().getName(),
                execution.getWorkflow().getUserId(),
                failedNodeId,
                errorSummary,
                retryExhausted));
        } catch (Exception e) {
            log.warn("[Runtime] 실패 알림 발신 실패 — executionId: {}, 실행 처리는 계속한다",
                execution.getId(), e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // fan-out 위상 스케줄러 보조
    // ──────────────────────────────────────────────────────────────────────

    /**
     * ready 큐의 노드들을 워커 풀에 제출한다(병렬 실행 시작).
     * 입력 변수 치환·nodeStarted 이벤트는 메인 스레드에서 수행하고, 워커는 executor.execute만 실행한다.
     * 이 노드가 ready가 된 시점에는 모든 live 부모가 완료되어 컨텍스트에 output이 존재한다.
     *
     * <p>{@code preCompleted}에 있는 노드는 executor를 부르지 않고 주어진 출력을 성공 결과로 즉시
     * 돌려준다(재처리 스킵). 메인 루프의 결과 처리·컨텍스트 갱신·엣지 전파는 일반 노드와 같은
     * 경로를 타므로 스케줄러 상태 관리가 갈라지지 않는다.
     *
     * @return 제출한 노드 수
     */
    private int dispatch(Deque<Node> ready, ExecutionCursor cursor,
                         CompletionService<NodeOutcome> completion, UUID executionId,
                         UUID workflowId,
                         Map<String, Map<String, Object>> preCompleted) {
        int count = 0;
        while (!ready.isEmpty()) {
            Node node = ready.poll();
            // executor 존재는 execute() 진입부에서 사전 검증됨 — 여기선 안전망.
            NodeExecutor executor = executorMap.get(node.getType());
            if (executor == null) {
                throw new IllegalStateException("NodeExecutor 없음 — type: " + node.getType());
            }
            Map<String, Object> preOutput = preCompleted.get(node.getId());
            if (preOutput != null) {
                log.info("[Runtime] 노드 스킵(재처리 — 원 실행 성공분 재사용) — nodeId: {}", node.getId());
                eventPublisher.publish(executionId, ExecutionEvent.nodeStarted(
                    executionId, workflowId, node.getId(), node.getType()));
                // 입력 렌더링도 하지 않는다 — 실행하지 않을 노드의 config를 치환할 이유가 없다.
                completion.submit(() -> new NodeOutcome(node, Map.of(),
                    ExecutorResult.success(preOutput, 0L), 0L, 0, false, true));
                count++;
                continue;
            }
            Map<String, Object> input = prepareNodeInput(node, cursor);
            RetryPolicy policy = RetryPolicy.from(node.getConfig(), node.getType(), retryProperties);
            // attempt 회차와 무관한 노드 고정 키 — IdempotencyKeys.generate가 이를 보장한다.
            String idempotencyKey = IdempotencyKeys.generate(executionId.toString(), node.getId());
            log.info("[Runtime] 노드 실행 — nodeId: {}, type: {}", node.getId(), node.getType());
            eventPublisher.publish(executionId, ExecutionEvent.nodeStarted(
                executionId, workflowId, node.getId(), node.getType()));
            completion.submit(() -> {
                long overallStart = System.currentTimeMillis();
                ExecutorResult result;
                int attempt = 1;
                while (true) {
                    long attemptStart = System.currentTimeMillis();
                    try {
                        result = executor.execute(node, input, cursor,
                            new NodeExecutor.NodeAttempt(attempt, idempotencyKey, policy));
                    } catch (Exception ex) {
                        result = ExecutorResult.failure(ex.toString(),
                            System.currentTimeMillis() - attemptStart, FailureClassifier.fromException(ex));
                    }
                    // MARKER는 "재시도를 포기한다"는 모드다(IdempotencyMode.MARKER 참고) — 2회차를
                    // 시작하면 attempt 1의 원 실패가 executor의 마커 차단(CLIENT_ERROR)으로 덮여써져
                    // node_runs 진단 정보와 retryExhausted 신호가 왜곡된다. 그래서 attempt 1 이후
                    // 곧바로 멈춘다(리뷰 I-1). executor의 마커 차단 분기는 다중 인스턴스 등을 대비한
                    // 방어선으로 남긴다.
                    if (result.isSuccess() || !policy.retryable(result.getFailureKind())
                            || attempt == policy.maxAttempts()
                            || policy.idempotency().usesMarker()) {
                        break;
                    }
                    long waitMs = policy.backoffMillis(attempt, random);
                    log.warn("[Runtime] 노드 재시도 — nodeId: {}, attempt: {}/{}, failureKind: {}, waitMs: {}",
                        node.getId(), attempt, policy.maxAttempts(), result.getFailureKind(), waitMs);
                    try {
                        // ponytail: 이 sleep이 워커 슬롯(workflow.execution.parallelism, 기본 4)을 점유한다.
                        // fan-out이 넓고 동시 재시도가 몰리면 슬롯이 고갈된다.
                        // 개선 경로: 재시도를 워커에 붙잡아두지 말고 큐에 되돌려 넣기(지연 재제출)
                        // waitMs는 backoffMillis()가 0 이상으로 clamp하지만, 방어적으로 한 번 더 하한
                        Thread.sleep(Math.max(0L, waitMs));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    attempt++;
                }
                // 재시도 루프 전체가 끝난 뒤 1회만 마커 해제 — 개별 attempt에서 해제하면 다음 attempt의
                // markInFlight가 항상 true를 반환해 MARKER 모드가 무력화된다(IdempotencyStore 계약).
                if (policy.idempotency().usesMarker()) {
                    idempotencyStore.clearInFlight(idempotencyKey);
                }
                long durationMs = System.currentTimeMillis() - overallStart;
                // 재시도 대상 실패로 maxAttempts까지 다 쓰고도 실패한 경우만 "소진"이다.
                // attempt==maxAttempts를 직접 확인해야 한다 — 인터럽트로 attempt++ 이전에 break한 경우
                // (attempt < maxAttempts)까지 소진으로 오판정하지 않기 위함.
                boolean retryExhausted = !result.isSuccess() && attempt > 1
                    && attempt == policy.maxAttempts()
                    && policy.retryable(result.getFailureKind());
                return new NodeOutcome(node, input, result, durationMs, attempt, retryExhausted, false);
            });
            count++;
        }
        return count;
    }

    /**
     * 방금 성공한 노드의 outgoing 엣지를 해소하고, 모든 입력이 해소되어 실행 가능한 노드를 readyOut에 담는다.
     * CONDITION은 매칭된 분기만 live이며, 모든 입력이 dead인 노드는 skip하고 그 하위로 dead를 전파한다(가지치기).
     */
    private void propagate(Node node, ExecutionCursor cursor,
                           Map<String, Integer> pending, Map<String, Boolean> hasLive,
                           Set<String> resolved, Deque<Node> readyOut) {
        List<Edge> live = cursor.liveOutgoingEdges(node);
        Deque<String> deadSources = new ArrayDeque<>();
        resolveEdges(node.getId(), live, true, cursor, pending, hasLive, resolved, readyOut, deadSources);
        while (!deadSources.isEmpty()) {
            String deadId = deadSources.poll();
            resolveEdges(deadId, List.of(), false, cursor, pending, hasLive, resolved, readyOut, deadSources);
        }
    }

    /** source 노드의 각 outgoing 엣지에 대해 target의 pending을 감소시키고, 해소 완료 시 ready/skip을 결정한다. */
    private void resolveEdges(String sourceId, List<Edge> liveEdges, boolean sourceLive,
                              ExecutionCursor cursor, Map<String, Integer> pending,
                              Map<String, Boolean> hasLive, Set<String> resolved,
                              Deque<Node> readyOut, Deque<String> deadSources) {
        for (Edge e : cursor.outgoingEdges(sourceId)) {
            String target = e.getTarget();
            Integer p = pending.get(target);
            if (p == null || resolved.contains(target)) {
                continue;
            }
            if (sourceLive && liveEdges.contains(e)) {
                hasLive.put(target, true);
            }
            pending.put(target, p - 1);
            if (pending.get(target) == 0) {
                if (Boolean.TRUE.equals(hasLive.get(target))) {
                    Node t = cursor.findNode(target);
                    if (t != null) {
                        readyOut.add(t);
                    }
                } else {
                    // 모든 입력이 dead → 노드 skip 후 하위로 dead 전파
                    resolved.add(target);
                    log.debug("[Runtime] 노드 skip(죽은 분기) — nodeId: {}", target);
                    deadSources.add(target);
                }
            }
        }
    }

    /** 워크플로우 그래프에 구조적 사이클이 있으면 예외를 던진다(Kahn 위상정렬 기반). */
    private void assertNoCycle(List<Node> nodes, List<Edge> edges) {
        Map<String, Integer> indeg = new HashMap<>();
        for (Node n : nodes) {
            indeg.put(n.getId(), 0);
        }
        for (Edge e : edges) {
            if (indeg.containsKey(e.getTarget())) {
                indeg.merge(e.getTarget(), 1, Integer::sum);
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        indeg.forEach((id, d) -> {
            if (d == 0) {
                queue.add(id);
            }
        });
        int processed = 0;
        while (!queue.isEmpty()) {
            String id = queue.poll();
            processed++;
            for (Edge e : edges) {
                if (e.getSource().equals(id) && indeg.containsKey(e.getTarget())) {
                    if (indeg.merge(e.getTarget(), -1, Integer::sum) == 0) {
                        queue.add(e.getTarget());
                    }
                }
            }
        }
        if (processed < nodes.size()) {
            throw new IllegalStateException("순환 참조 감지 — 워크플로우 그래프에 사이클이 있습니다.");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 보조 메서드
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 노드의 config를 복사한 뒤 모든 String 값에 변수 치환을 적용한다.
     * Map/List 타입의 중첩 값도 재귀적으로 처리한다.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> prepareNodeInput(Node node, ExecutionCursor cursor) {
        if (node.getConfig() == null) {
            return new HashMap<>();
        }
        return renderMap(node.getConfig(), cursor);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> renderMap(Map<String, Object> source, ExecutionCursor cursor) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            result.put(entry.getKey(), renderValue(entry.getValue(), cursor));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object renderValue(Object value, ExecutionCursor cursor) {
        if (value instanceof String s) {
            return cursor.renderVariables(s);
        }
        if (value instanceof Map<?, ?> map) {
            return renderMap((Map<String, Object>) map, cursor);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> renderValue(item, cursor)).toList();
        }
        return value;
    }

    private void saveExecutionLog(
        WorkflowExecution execution,
        Node node,
        Map<String, Object> input,
        ExecutorResult result,
        long durationMs,
        int attemptCount,
        boolean skipped
    ) {
        try {
            String inputJson = objectMapper.writeValueAsString(SensitiveDataMasker.mask(input));
            String outputJson = result.isSuccess()
                ? objectMapper.writeValueAsString(SensitiveDataMasker.mask(result.getOutput()))
                : null;

            ExecutorResult.TokenUsage usage = result.getUsage();

            WorkflowExecutionLog logEntry = WorkflowExecutionLog.builder()
                .execution(execution)
                .nodeId(node.getId())
                .nodeType(node.getType())
                .status(skipped ? ExecutionLogStatus.SKIPPED
                    : result.isSuccess() ? ExecutionLogStatus.SUCCESS : ExecutionLogStatus.FAILED)
                .inputJson(inputJson)
                .outputJson(outputJson)
                .errorMessage(result.getErrorMessage())
                .durationMs(durationMs)
                .traceId(execution.getTraceId())
                .promptTokens(usage != null ? usage.promptTokens() : null)
                .completionTokens(usage != null ? usage.completionTokens() : null)
                .totalTokens(usage != null ? usage.totalTokens() : null)
                .attemptCount(attemptCount)
                .build();

            executionLogRepository.save(logEntry);
        } catch (Exception e) {
            // 로그 저장 실패가 실행 전체를 중단시키지 않도록 경고만 기록
            log.warn("[Runtime] 실행 로그 저장 실패 — nodeId: {}", node.getId(), e);
        }
    }
}
