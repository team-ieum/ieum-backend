package com.ieum.workflowcore.executor;

import com.ieum.workflowcore.executor.dto.AgentExecutionResult;
import com.ieum.workflowcore.executor.dto.AgentNodeRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

@Slf4j
@Component
public class AgentNodeExecutor {

    private final WebClient webClient;

    public AgentNodeExecutor(@Value("${ieum.agent.url}") String agentBaseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(agentBaseUrl)
                .build();
    }

    public AgentExecutionResult execute(AgentNodeRequest request, String llmProvider, String llmApiKey) {
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
            log.error("[AgentNodeExecutor] 에이전트 서비스 오류 - status: {}, body: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return new AgentExecutionResult(false, null, null,
                    "에이전트 서비스 오류 (HTTP " + e.getStatusCode().value() + "): " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("[AgentNodeExecutor] 에이전트 실행 중 예외 발생", e);
            return new AgentExecutionResult(false, null, null,
                    "에이전트 실행 중 오류가 발생했습니다: " + e.getMessage());
        }
    }
}
