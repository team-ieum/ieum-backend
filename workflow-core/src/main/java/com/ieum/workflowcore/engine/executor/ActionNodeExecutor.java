package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.FailureClassifier;
import com.ieum.workflowcore.engine.FailureKind;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.engine.RetryPolicy;
import com.ieum.workflowcore.engine.executor.dto.ActionExecutionResult;
import com.ieum.workflowcore.engine.executor.dto.ActionNodeRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * ACTION 노드 Executor — 앱 도구 하나를 LLM 없이 실행하는 결정론적 노드(ADR-005).
 *
 * <p>{@code config.tools[0]}({@code {"name": "<tool_key>", "config": {...}}})을 ieum-agent
 * {@code POST /v1/actions/execute}에 위임한다. AI 노드와 같은 도구 전처리({@link ToolCallPreparer})를 쓰고,
 * workflow-core 안에서 {@code ${ieum.agent.url}} 기반 WebClient로 직접 호출한다(api 모듈 {@code AgentClient}는
 * 의존 방향상 쓸 수 없다).
 *
 * <p>LLM 전용 처리가 없다 — LLM 헤더·베타 키 모드·쿼터 예약·토큰 차감·모델 fallback 모두 없고 과금은 0이다.
 * 노드 출력은 agent {@code output} dict 그대로다(AI 노드의 {@code {output, metadata}} 래퍼를 쓰지 않는다).
 *
 * <p>쓰기 도구를 실행하므로 멱등성 가드({@code X-Idempotency-Key}·MARKER)를 AI 노드와 같은 규칙으로 적용한다.
 * 도구 오류({@code ACTION_TOOL_FAILED})와 알 수 없는 toolKey(HTTP 400)는 {@code CLIENT_ERROR}라 재시도하지 않는다.
 */
@Slf4j
@Component
public class ActionNodeExecutor implements NodeExecutor {

    private static final String ACTIONS_PATH = "/v1/actions/execute";
    // 정적 WebClient.builder()는 Boot codec 설정을 받지 않아 기본 256KB에 묶인다.
    // agent 읽기 도구는 본문을 최대 1MB까지 돌려주므로(google_drive_read) JSON 이스케이프 여유를 둬 4MB.
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final WebClient webClient;
    private final ToolCallPreparer toolCallPreparer;
    private final UserRoleProvider userRoleProvider;
    private final IdempotencyStore idempotencyStore;
    private final int agentTimeoutSeconds;

    public ActionNodeExecutor(
        @Value("${ieum.agent.url}") String agentBaseUrl,
        ToolCallPreparer toolCallPreparer,
        UserRoleProvider userRoleProvider,
        IdempotencyStore idempotencyStore,
        @Value("${ieum.agent.timeout-seconds:120}") int agentTimeoutSeconds
    ) {
        this.webClient = WebClient.builder()
            .baseUrl(agentBaseUrl)
            .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
            .build();
        this.toolCallPreparer = toolCallPreparer;
        this.userRoleProvider = userRoleProvider;
        this.idempotencyStore = idempotencyStore;
        this.agentTimeoutSeconds = agentTimeoutSeconds;
    }

    @Override
    public NodeType getNodeType() {
        return NodeType.ACTION;
    }

    @Override
    public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
        return execute(node, input, cursor, NodeAttempt.NONE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor, NodeAttempt attempt) {
        long startTime = System.currentTimeMillis();
        RetryPolicy policy = attempt.policy();

        try {
            Object rawTools = node.getConfig() == null ? null : node.getConfig().get("tools");
            if (!(rawTools instanceof List<?> list) || list.isEmpty()) {
                return ExecutorResult.failure("ACTION 노드에는 config.tools[0]이 필요합니다.",
                    System.currentTimeMillis() - startTime, FailureKind.CLIENT_ERROR);
            }
            // 전처리 전에 이름부터 확인한다 — 이름 없는 도구를 토큰·헤더 해석에 태우지 않는다.
            Object first = list.get(0);
            Object rawName = first instanceof Map<?, ?> entry ? entry.get("name") : first;
            if (!(rawName instanceof String name) || name.isBlank()) {
                return ExecutorResult.failure("ACTION 노드의 tools[0].name이 필요합니다.",
                    System.currentTimeMillis() - startTime, FailureKind.CLIENT_ERROR);
            }

            // 첫 도구만 쓴다 — 나머지 도구가 Google 토큰 조회·인증 헤더에 끼어들지 못하게 전처리 앞에서 자른다.
            ToolCallPreparer.PreparedTools prepared = toolCallPreparer.prepare(List.of(first), cursor);
            Map<String, Object> tool = prepared.tools().get(0);
            String toolKey = (String) tool.get("name");
            // config 값은 로그에 싣지 않는다 — slack·discord는 복호화된 웹훅 URL이 들어 있다.
            log.info("[ActionNodeExecutor] 노드 실행 — nodeId: {}, toolKey: {}", node.getId(), toolKey);

            Map<String, Object> toolConfig = tool.get("config") instanceof Map<?, ?> configMap
                ? new HashMap<>((Map<String, Object>) configMap) : new HashMap<>();
            UUID userId = cursor.getContext().getUserId();
            String userRole = userId != null ? userRoleProvider.findRoleByUserId(userId) : null;

            if (policy != null && policy.idempotency().usesMarker()) {
                if (!idempotencyStore.markInFlight(attempt.idempotencyKey(), NodeExecutor.markerTtl(policy))) {
                    log.warn("[ActionNodeExecutor] 멱등성 마커 충돌로 호출 차단 — nodeId: {}", node.getId());
                    return ExecutorResult.failure(
                        "중복 호출 차단 — 이전 시도가 외부 서비스에 도달했을 수 있어 재시도를 중단합니다.",
                        System.currentTimeMillis() - startTime, FailureKind.CLIENT_ERROR);
                }
            }

            // AI 노드와 같은 기준: 재시도가 꺼져 있으면(isDisabled) 헤더를 붙이지 않는다.
            String idempotencyHeaderKey =
                (policy != null && policy.idempotency().usesHeader() && !policy.isDisabled())
                    ? attempt.idempotencyKey() : null;

            ActionNodeRequest request = ActionNodeRequest.builder()
                .nodeId(node.getId())
                .toolKey(toolKey)
                .config(toolConfig)
                .build();
            ActionExecutionResult result = callAgentService(
                request, userId, userRole, prepared, cursor.getContext().getTraceId(), idempotencyHeaderKey);

            if (result == null) {
                return ExecutorResult.failure("에이전트 응답이 비어 있습니다.",
                    System.currentTimeMillis() - startTime, FailureKind.UNKNOWN);
            }
            if (!result.isSuccess()) {
                log.error("[ActionNodeExecutor] 도구 실행 실패 — nodeId: {}, toolKey: {}, errorCode: {}",
                    node.getId(), toolKey, result.getErrorCode());
                return ExecutorResult.failure(
                    result.getErrorMessage() != null ? result.getErrorMessage() : "ACTION 도구 실행에 실패했습니다.",
                    System.currentTimeMillis() - startTime,
                    FailureClassifier.fromAgentErrorCode(result.getErrorCode()));
            }

            Map<String, Object> output = result.getOutput() != null ? result.getOutput() : new HashMap<>();
            log.info("[ActionNodeExecutor] 도구 실행 성공 — nodeId: {}, toolKey: {}", node.getId(), toolKey);
            return ExecutorResult.success(output, System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("[ActionNodeExecutor] 실행 실패 — nodeId: {}", node.getId(), e);
            return ExecutorResult.failure(e.getMessage(), System.currentTimeMillis() - startTime,
                FailureClassifier.fromException(e));
        }
    }

    private ActionExecutionResult callAgentService(
        ActionNodeRequest request, UUID userId, String userRole,
        ToolCallPreparer.PreparedTools prepared, String traceId, String idempotencyKey
    ) {
        try {
            WebClient.RequestBodySpec requestSpec = webClient.post()
                .uri(ACTIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Node-Id", request.getNodeId());

            // 헤더가 없으면 agent가 자체 uuid4를 만들어 BE 이력과 조인이 끊기므로 있을 때만 보낸다.
            if (traceId != null) {
                requestSpec = requestSpec.header("X-Trace-Id", traceId);
            }
            // 같은 키의 재요청은 agent가 도구를 다시 돌리지 않고 저장된 응답을 돌려준다.
            if (idempotencyKey != null) {
                requestSpec = requestSpec.header("X-Idempotency-Key", idempotencyKey);
            }
            if (userId != null) {
                requestSpec = requestSpec.header("X-User-Id", userId.toString());
                if (userRole != null) {
                    requestSpec = requestSpec.header("X-User-Role", userRole);
                }
            }
            if (prepared.googleAccessToken() != null) {
                requestSpec = requestSpec.header("X-Google-Access-Token", prepared.googleAccessToken());
            }
            for (Map.Entry<String, String> entry : prepared.authHeaders().entrySet()) {
                requestSpec = requestSpec.header(entry.getKey(), entry.getValue());
                log.debug("[ActionNodeExecutor] 도구 인증 헤더 주입 — header: {}", entry.getKey());
            }

            return requestSpec
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ActionExecutionResult.class)
                .timeout(Duration.ofSeconds(agentTimeoutSeconds))
                .block();
        } catch (WebClientResponseException e) {
            log.error("[ActionNodeExecutor] 에이전트 서비스 오류 — status: {}, body: {}",
                e.getStatusCode(), e.getResponseBodyAsString());
            return new ActionExecutionResult(false, null,
                "에이전트 서비스 오류 (HTTP " + e.getStatusCode().value() + "): " + e.getResponseBodyAsString(),
                FailureClassifier.agentServiceErrorCode(e.getStatusCode().value()));
        }
    }
}
