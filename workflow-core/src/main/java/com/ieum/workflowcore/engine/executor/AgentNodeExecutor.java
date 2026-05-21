package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.engine.executor.dto.AgentExecutionResult;
import com.ieum.workflowcore.engine.executor.dto.AgentNodeRequest;
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

    private final WebClient webClient;
    private final CredentialProvider credentialProvider;
    private final GoogleTokenProvider googleTokenProvider;
    private final ToolAuthResolver toolAuthResolver;
    private final int agentTimeoutSeconds;

    public AgentNodeExecutor(
        @Value("${ieum.agent.url}") String agentBaseUrl,
        CredentialProvider credentialProvider,
        GoogleTokenProvider googleTokenProvider,
        ToolAuthResolver toolAuthResolver,
        @Value("${ieum.agent.timeout-seconds:120}") int agentTimeoutSeconds
    ) {
        this.webClient = WebClient.builder()
            .baseUrl(agentBaseUrl)
            .build();
        this.credentialProvider = credentialProvider;
        this.googleTokenProvider = googleTokenProvider;
        this.toolAuthResolver = toolAuthResolver;
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

        try {
            Map<String, Object> config = node.getConfig();

            String llmProvider = (String) config.get("llmProvider");
            String credentialId = (String) config.get("credentialId");
            String promptTemplate = (String) config.getOrDefault("prompt", "");
            String promptTemplateId = (String) config.get("promptTemplateId");
            String systemMessage = (String) config.get("systemMessage");
            String model = (String) config.get("model");
            String agentType = (String) config.getOrDefault("agentType", "simple");
            List<Map<String, Object>> tools = (List<Map<String, Object>>) config.get("tools");

            String renderedPrompt = cursor.renderVariables(promptTemplate);
            log.debug("[AgentNodeExecutor] 렌더링된 프롬프트 길이: {}", renderedPrompt.length());

            String decryptedApiKey = credentialProvider.getDecryptedApiKey(credentialId);

            String googleAccessToken = resolveGoogleAccessToken(tools, cursor);
            UUID userId = cursor.getContext().getUserId();
            Map<String, String> toolAuthHeaders = toolAuthResolver.resolveHeaders(tools, userId);

            AgentNodeRequest request = AgentNodeRequest.builder()
                .nodeId(node.getId())
                .promptTemplateId(promptTemplateId)
                .renderedPrompt(renderedPrompt)
                .systemMessage(systemMessage)
                .model(model)
                .agentType(agentType)
                .tools(tools)
                .workflowContext(cursor.getContext().getNodeOutputs())
                .build();

            AgentExecutionResult agentResult = callAgentService(
                request, llmProvider, decryptedApiKey, googleAccessToken,
                userId, toolAuthHeaders);

            if (!agentResult.isSuccess()) {
                log.error("[AgentNodeExecutor] 에이전트 실행 실패 — nodeId: {}, error: {}",
                    node.getId(), agentResult.getErrorMessage());
                return ExecutorResult.failure(agentResult.getErrorMessage(),
                    System.currentTimeMillis() - startTime);
            }

            Map<String, Object> output = new HashMap<>();
            output.put("output", agentResult.getOutput());
            output.put("metadata", agentResult.getMetadata());

            log.info("[AgentNodeExecutor] 에이전트 실행 성공 — nodeId: {}", node.getId());
            return ExecutorResult.success(output, System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("[AgentNodeExecutor] 실행 실패 — nodeId: {}", node.getId(), e);
            return ExecutorResult.failure(e.getMessage(), System.currentTimeMillis() - startTime);
        }
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
        String googleAccessToken, UUID userId, Map<String, String> toolAuthHeaders
    ) {
        try {
            WebClient.RequestBodySpec requestSpec = webClient.post()
                .uri("/v1/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-LLM-Provider", llmProvider)
                .header("X-LLM-Api-Key", llmApiKey);

            if (userId != null) {
                requestSpec = requestSpec.header("X-User-Id", userId.toString());
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
                    + e.getResponseBodyAsString());
        }
    }
}
