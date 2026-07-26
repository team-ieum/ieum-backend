package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.engine.executor.dto.AgentExecutionResult;
import com.ieum.workflowcore.engine.executor.dto.AgentNodeRequest;
import com.ieum.workflowcore.engine.executor.dto.McpServerRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * AI 노드 Executor.
 *
 * <p>Python ieum-agent 서비스에 AI 실행을 위임하는 WebClient 기반 구현체.
 * {@link NodeExecutor}를 구현하여 {@code SyncExecutionRuntime}의 executorMap에 자동 등록된다.
 *
 * <p>config 형식:
 * <pre>{@code
 * {
 *   "llmProvider":      "CLAUDE",
 *   "credentialId":     "uuid",
 *   "prompt":           "사용자 {{nodes.node-1.output.userName}}에게 이메일 발송",
 *   "promptTemplateId": "template-123",   // nullable
 *   "tools":            [...]              // nullable
 * }
 * }</pre>
 */
@Slf4j
@Component
public class AgentNodeExecutor implements NodeExecutor {

    private static final String GOOGLE_BUILTIN_PREFIX = "builtin:google_";
    private static final String MCP_TOOL_NAME = "mcp";
    private static final java.util.Set<String> WEBHOOK_TOOL_NAMES = java.util.Set.of("slack", "discord");

    private final WebClient webClient;
    private final CredentialProvider credentialProvider;
    private final GoogleTokenProvider googleTokenProvider;
    private final ToolAuthResolver toolAuthResolver;
    private final McpCatalogProvider mcpCatalogProvider;
    private final WebhookCredentialProvider webhookCredentialProvider;
    private final UserRoleProvider userRoleProvider;
    private final BetaPlatformProvider betaPlatformProvider;
    private final int agentTimeoutSeconds;

    public AgentNodeExecutor(
        @Value("${ieum.agent.url}") String agentBaseUrl,
        CredentialProvider credentialProvider,
        GoogleTokenProvider googleTokenProvider,
        ToolAuthResolver toolAuthResolver,
        McpCatalogProvider mcpCatalogProvider,
        WebhookCredentialProvider webhookCredentialProvider,
        UserRoleProvider userRoleProvider,
        BetaPlatformProvider betaPlatformProvider,
        @Value("${ieum.agent.timeout-seconds:120}") int agentTimeoutSeconds
    ) {
        this.webClient = WebClient.builder()
            .baseUrl(agentBaseUrl)
            .build();
        this.credentialProvider = credentialProvider;
        this.googleTokenProvider = googleTokenProvider;
        this.toolAuthResolver = toolAuthResolver;
        this.mcpCatalogProvider = mcpCatalogProvider;
        this.webhookCredentialProvider = webhookCredentialProvider;
        this.userRoleProvider = userRoleProvider;
        this.betaPlatformProvider = betaPlatformProvider;
        this.agentTimeoutSeconds = agentTimeoutSeconds;
    }

    @Override
    public NodeType getNodeType() {
        return NodeType.AI;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
        long startTime = System.currentTimeMillis();
        log.info("[AgentNodeExecutor] 노드 실행 — nodeId: {}", node.getId());

        UUID userId = null;
        boolean useBetaPlatformKey = false;
        String betaReservationKey = null;

        try {
            Map<String, Object> config = node.getConfig();

            String llmProvider = (String) config.get("llmProvider");
            String credentialId = (String) config.get("credentialId");
            String promptTemplate = (String) config.getOrDefault("prompt", "");
            String promptTemplateId = (String) config.get("promptTemplateId");
            String systemMessage = (String) config.get("systemMessage");
            String model = (String) config.get("model");
            String agentType = (String) config.getOrDefault("agentType", "simple");
            List<Map<String, Object>> tools = parseTools(config.get("tools"));

            String renderedPrompt = cursor.renderVariables(promptTemplate);
            log.debug("[AgentNodeExecutor] 렌더링된 프롬프트 길이: {}", renderedPrompt.length());

            userId = cursor.getContext().getUserId();
            String userRole = userId != null ? userRoleProvider.findRoleByUserId(userId) : null;

            String googleAccessToken = resolveGoogleAccessToken(tools, cursor);
            Map<String, String> toolAuthHeaders = toolAuthResolver.resolveHeaders(tools, userId);
            List<McpServerRef> mcpServers = resolveMcpServers(tools, userId);
            injectWebhookUrls(tools, userId);

            AgentNodeRequest request = AgentNodeRequest.builder()
                .nodeId(node.getId())
                .promptTemplateId(promptTemplateId)
                .renderedPrompt(renderedPrompt)
                .systemMessage(systemMessage)
                .model(model)
                .agentType(agentType)
                .tools(tools)
                .workflowContext(cursor.getContext().getNodeOutputs())
                .mcpServers(mcpServers.isEmpty() ? null : mcpServers)
                .build();

            // credentialId가 없으면 키 없이 전달 — self-hosted 자격(ADMIN/TESTER role)이 최우선이며,
            // 이 경우 agent가 라우팅/차단을 판단한다(ChatService.isSelfHostedEligible과 동일 우선순위).
            // self-hosted 자격이 없을 때만 베타 자격(User.betaAccess)을 확인해 플랫폼 Gemini 키로 폴백한다.
            // 쿼터 예약(INCR)은 위 사전작업이 모두 끝난 뒤, agent 호출 바로 직전에 한다 — 그 앞에서 예외가
            // 나면 INCR 자체가 없으므로 환불이 필요 없다. 쿼터 초과로 reserveQuota가 거부하면
            // BetaQuotaService가 스스로 카운터를 원복하므로 여기서도 별도 환불을 시도하지 않는다
            // (useBetaPlatformKey는 reserveQuota가 예외 없이 반환한 뒤에만 true가 된다).
            String decryptedApiKey = null;
            if (credentialId != null && !credentialId.isBlank()) {
                decryptedApiKey = credentialProvider.getDecryptedApiKey(credentialId);
            } else if (!isSelfHostedEligible(userRole) && userId != null && betaPlatformProvider.isBetaEligible(userId)) {
                betaReservationKey = betaPlatformProvider.reserveQuota(userId);
                useBetaPlatformKey = true;
            }

            AgentExecutionResult agentResult = callAgentService(
                request, llmProvider, decryptedApiKey, googleAccessToken,
                userId, userRole, toolAuthHeaders, useBetaPlatformKey,
                cursor.getContext().getTraceId());

            if (!agentResult.isSuccess()) {
                releaseBetaQuotaOnFailure(betaReservationKey);
                log.error("[AgentNodeExecutor] 에이전트 실행 실패 — nodeId: {}, error: {}",
                    node.getId(), agentResult.getErrorMessage());
                return ExecutorResult.failure(agentResult.getErrorMessage(),
                    System.currentTimeMillis() - startTime);
            }

            // 베타 플랫폼 키를 사용했다면 응답 usage.totalTokens로 사용량을 사후 차감한다.
            // best-effort — 이미 성공(과금)한 호출을 토큰 회계 실패로 실패 반환시키지 않는다.
            // (호출 전 reserveQuota는 반대로 예외를 그대로 전파해 사전 차단한다.)
            if (useBetaPlatformKey && userId != null && agentResult.getUsage() != null
                && agentResult.getUsage().getTotalTokens() != null) {
                try {
                    betaPlatformProvider.recordTokens(userId, agentResult.getUsage().getTotalTokens());
                } catch (Exception e) {
                    log.warn("[AgentNodeExecutor] 베타 토큰 사용량 기록 실패 — nodeId: {}, userId: {}",
                        node.getId(), userId, e);
                }
            }

            Map<String, Object> output = new HashMap<>();
            output.put("output", agentResult.getOutput());
            output.put("metadata", agentResult.getMetadata());

            log.info("[AgentNodeExecutor] 에이전트 실행 성공 — nodeId: {}", node.getId());
            return ExecutorResult.success(output, System.currentTimeMillis() - startTime,
                toTokenUsage(agentResult.getUsage()));

        } catch (Exception e) {
            releaseBetaQuotaOnFailure(betaReservationKey);
            log.error("[AgentNodeExecutor] 실행 실패 — nodeId: {}", node.getId(), e);
            return ExecutorResult.failure(e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    /** 자체 호스팅 LLM(키 없음) 경로 자격 — ChatService.isSelfHostedEligible과 동일 판정. */
    private static boolean isSelfHostedEligible(String userRole) {
        return "ROLE_ADMIN".equals(userRole) || "ROLE_TESTER".equals(userRole);
    }

    /**
     * 베타 platform 키로 쿼터를 예약(INCR)했는데 이후 agent 호출이 실패/예외로 끝난 경우에만
     * 일일 호출 카운터를 환불한다(best-effort). reserveQuota 자체가 실패(쿼터 초과)한 경우는
     * betaReservationKey가 세팅되지 않으므로 이 메서드가 호출돼도 자연히 무시된다.
     *
     * @param betaReservationKey reserveQuota가 반환한 키(자정 경계에도 reserve와 동일한 날짜 키를 환불)
     */
    private void releaseBetaQuotaOnFailure(String betaReservationKey) {
        if (betaReservationKey == null) {
            return;
        }
        try {
            betaPlatformProvider.releaseDailyCall(betaReservationKey);
        } catch (Exception e) {
            log.warn("[AgentNodeExecutor] 베타 일일 카운터 환불 실패 — key: {}", betaReservationKey, e);
        }
    }

    /** agent 응답 usage를 엔진 표준 타입으로 변환한다. usage가 없으면 null. */
    private static ExecutorResult.TokenUsage toTokenUsage(AgentExecutionResult.Usage usage) {
        if (usage == null) {
            return null;
        }
        return new ExecutorResult.TokenUsage(
            usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }

    /**
     * config.tools를 {@code List<Map<String, Object>>}로 변환한다.
     *
     * <p>LLM이 tools를 {@code ["web_search"]} 형태의 String 배열로 반환하는 경우
     * {@code [{"name": "web_search"}]} 형태의 Map 배열로 변환한다.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseTools(Object rawTools) {
        if (rawTools == null) return null;
        List<?> list = (List<?>) rawTools;
        if (list.isEmpty()) return new ArrayList<>();

        if (list.get(0) instanceof String) {
            return list.stream()
                .map(t -> (Map<String, Object>) Map.of("name", t))
                .collect(Collectors.toList());
        }
        return (List<Map<String, Object>>) rawTools;
    }

    /**
     * Google 빌트인 도구({@code builtin:google_*})가 tools 목록에 포함된 경우
     * Google Access Token을 조회하여 반환한다.
     *
     * <p>Google 도구가 없거나 userId가 없으면 {@code null}을 반환한다.
     * 토큰 조회 실패 시 예외를 전파하여 노드 실행 실패로 처리한다.
     *
     * @param tools  노드 config의 tools 목록 (nullable)
     * @param cursor 실행 커서 (userId 포함)
     * @return Google Access Token 원문, 또는 {@code null}
     */
    /**
     * 노드 tools에서 'mcp' 도구의 catalogId를 수집하여 카탈로그에서 MCP 서버 정보를 조회한다.
     *
     * <p>mcp 도구 형식: {@code {"name": "mcp", "config": {"catalogId": "<UUID>"}}}.
     * catalogId가 없거나 userId가 없으면 해당 항목을 건너뛰며, 결과가 없으면 빈 리스트를 반환한다.
     */
    @SuppressWarnings("unchecked")
    private List<McpServerRef> resolveMcpServers(List<Map<String, Object>> tools, UUID userId) {
        if (tools == null || tools.isEmpty() || userId == null) {
            return List.of();
        }

        List<UUID> catalogIds = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            if (!MCP_TOOL_NAME.equals(tool.get("name"))) {
                continue;
            }
            Object cfg = tool.get("config");
            if (!(cfg instanceof Map<?, ?> configMap)) {
                continue;
            }
            Object rawId = configMap.get("catalogId");
            if (rawId == null) {
                continue;
            }
            try {
                catalogIds.add(UUID.fromString(rawId.toString()));
            } catch (IllegalArgumentException e) {
                log.warn("[AgentNodeExecutor] 잘못된 mcp catalogId 형식 — value: {}", rawId);
            }
        }

        if (catalogIds.isEmpty()) {
            return List.of();
        }
        return mcpCatalogProvider.resolveServers(catalogIds, userId);
    }

    /**
     * slack/discord 도구의 {@code config.webhookCredentialId}로 복호화된 webhook URL을 조회해
     * 도구 config에 {@code webhook_url}을 in-place 주입한다.
     *
     * <p>웹훅 URL은 노드 config에 영속 저장하지 않고(노출 시 누구나 발송 가능), 실행 시점에만
     * 자격증명 저장소에서 채워 ieum-agent로 전달한다. agent의 {@code _bind_config}가 webhook_url을
     * 도구 인자로 바인딩하므로, 매 실행마다 URL 유실 없이 발송된다.
     *
     * <p>도구 형식: {@code {"name": "slack"|"discord", "config": {"webhookCredentialId": "<UUID>"}}}.
     */
    @SuppressWarnings("unchecked")
    private void injectWebhookUrls(List<Map<String, Object>> tools, UUID userId) {
        log.info("[webhook-debug] injectWebhookUrls 진입 — tools 수: {}, userId: {}",
            tools != null ? tools.size() : 0, userId);
        if (tools == null || tools.isEmpty() || userId == null) {
            return;
        }

        for (Map<String, Object> tool : tools) {
            if (!WEBHOOK_TOOL_NAMES.contains(tool.get("name"))) {
                continue;
            }
            log.info("[webhook-debug] webhook 도구 발견 — name: {}, config: {}", tool.get("name"), tool.get("config"));
            Object cfg = tool.get("config");
            if (!(cfg instanceof Map<?, ?> configMap)) {
                log.warn("[webhook-debug] config가 Map이 아님 — name: {}, config: {}", tool.get("name"), cfg);
                continue;
            }
            Object rawId = configMap.get("webhookCredentialId");
            if (rawId == null) {
                log.warn("[webhook-debug] config에 webhookCredentialId 없음 — config keys: {}", configMap.keySet());
                continue;
            }
            UUID credentialId;
            try {
                credentialId = UUID.fromString(rawId.toString());
            } catch (IllegalArgumentException e) {
                log.warn("[AgentNodeExecutor] 잘못된 webhookCredentialId 형식 — value: {}", rawId);
                continue;
            }
            webhookCredentialProvider.resolveWebhookUrl(credentialId, userId)
                .ifPresentOrElse(
                    url -> {
                        Map<String, Object> mutableConfig = new HashMap<>((Map<String, Object>) configMap);
                        mutableConfig.put("webhook_url", url);
                        tool.put("config", mutableConfig);
                    },
                    () -> log.warn("[AgentNodeExecutor] 웹훅 자격증명 미해결 — credentialId: {}", credentialId)
                );
        }
    }

    private String resolveGoogleAccessToken(List<Map<String, Object>> tools, ExecutionCursor cursor) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }

        boolean hasGoogleTool = tools.stream()
            .map(tool -> (String) tool.get("name"))
            .filter(name -> name != null)
            .anyMatch(name -> name.startsWith(GOOGLE_BUILTIN_PREFIX));

        if (!hasGoogleTool) {
            return null;
        }

        UUID userId = cursor.getContext().getUserId();
        if (userId == null) {
            log.warn("[AgentNodeExecutor] Google 빌트인 도구 사용이지만 userId가 없음 — 토큰 없이 진행");
            return null;
        }

        log.debug("[AgentNodeExecutor] Google 빌트인 도구 감지 — userId: {} 로 토큰 조회", userId);
        return googleTokenProvider.getValidAccessToken(userId);
    }

    private AgentExecutionResult callAgentService(
        AgentNodeRequest request, String llmProvider, String llmApiKey,
        String googleAccessToken, UUID userId, String userRole, Map<String, String> toolAuthHeaders,
        boolean useBetaPlatformKey, String traceId
    ) {
        try {
            WebClient.RequestBodySpec requestSpec = webClient.post()
                .uri("/v1/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-LLM-Provider", useBetaPlatformKey ? "GEMINI" : llmProvider)
                .header("X-Node-Id", request.getNodeId());

            // 실행 단위 상관관계 ID — agent TraceIdMiddleware가 span 속성 ieum.trace_id로 부착한다.
            // 헤더가 없으면 agent가 자체 uuid4를 생성해버려 BE 이력과 조인이 끊기므로 있을 때만 보낸다.
            if (traceId != null) {
                requestSpec = requestSpec.header("X-Trace-Id", traceId);
            }

            if (useBetaPlatformKey) {
                // self-hosted 자격이면 execute()에서 베타 분기 자체에 진입하지 않으므로 여기 도달하지 않는다.
                requestSpec = requestSpec.header("X-Key-Mode", "platform");
            } else if (llmApiKey != null) {
                requestSpec = requestSpec.header("X-LLM-Api-Key", llmApiKey);
            }

            if (userId != null) {
                requestSpec = requestSpec.header("X-User-Id", userId.toString());
                if (userRole != null) {
                    requestSpec = requestSpec.header("X-User-Role", userRole);
                }
            }

            if (googleAccessToken != null) {
                requestSpec = requestSpec.header("X-Google-Access-Token", googleAccessToken);
                log.debug("[AgentNodeExecutor] X-Google-Access-Token 헤더 주입");
            }

            for (Map.Entry<String, String> entry : toolAuthHeaders.entrySet()) {
                requestSpec = requestSpec.header(entry.getKey(), entry.getValue());
                log.debug("[AgentNodeExecutor] 도구 인증 헤더 주입 — header: {}", entry.getKey());
            }

            return requestSpec
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AgentExecutionResult.class)
                .timeout(Duration.ofSeconds(agentTimeoutSeconds))
                .block();
        } catch (WebClientResponseException e) {
            log.error("[AgentNodeExecutor] 에이전트 서비스 오류 — status: {}, body: {}",
                e.getStatusCode(), e.getResponseBodyAsString());
            return new AgentExecutionResult(false, null, null,
                "에이전트 서비스 오류 (HTTP " + e.getStatusCode().value() + "): "
                    + e.getResponseBodyAsString(), null);
        }
    }
}
