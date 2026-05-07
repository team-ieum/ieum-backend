package com.ieum.workflowcore.executor;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.engine.executor.CredentialProvider;
import com.ieum.workflowcore.engine.executor.NodeExecutor;
import com.ieum.workflowcore.executor.dto.AgentExecutionResult;
import com.ieum.workflowcore.executor.dto.AgentNodeRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private final WebClient webClient;
    private final CredentialProvider credentialProvider;

    public AgentNodeExecutor(
        @Value("${ieum.agent.url}") String agentBaseUrl,
        CredentialProvider credentialProvider
    ) {
        this.webClient = WebClient.builder()
            .baseUrl(agentBaseUrl)
            .build();
        this.credentialProvider = credentialProvider;
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
            List<Map<String, Object>> tools = (List<Map<String, Object>>) config.get("tools");

            // 프롬프트 변수 치환
            String renderedPrompt = cursor.renderVariables(promptTemplate);
            log.debug("[AgentNodeExecutor] 렌더링된 프롬프트 길이: {}", renderedPrompt.length());

            // API Key 복호화
            String decryptedApiKey = credentialProvider.getDecryptedApiKey(credentialId);

            AgentNodeRequest request = AgentNodeRequest.builder()
                .nodeId(node.getId())
                .promptTemplateId(promptTemplateId)
                .renderedPrompt(renderedPrompt)
                .tools(tools)
                .build();

            AgentExecutionResult agentResult = callAgentService(request, llmProvider, decryptedApiKey);

            if (!agentResult.isSuccess()) {
                log.error("[AgentNodeExecutor] 에이전트 실행 실패 — nodeId: {}, error: {}",
                    node.getId(), agentResult.getErrorMessage());
                return ExecutorResult.failure(agentResult.getErrorMessage(), System.currentTimeMillis() - startTime);
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

    private AgentExecutionResult callAgentService(
        AgentNodeRequest request, String llmProvider, String llmApiKey
    ) {
        try {
            return webClient.post()
                .uri("/v1/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-LLM-Provider", llmProvider)
                .header("X-LLM-Api-Key", llmApiKey)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AgentExecutionResult.class)
                .timeout(Duration.ofSeconds(60))
                .block();
        } catch (WebClientResponseException e) {
            log.error("[AgentNodeExecutor] 에이전트 서비스 오류 — status: {}, body: {}",
                e.getStatusCode(), e.getResponseBodyAsString());
            return new AgentExecutionResult(false, null, null,
                "에이전트 서비스 오류 (HTTP " + e.getStatusCode().value() + "): " + e.getResponseBodyAsString());
        }
    }
}
