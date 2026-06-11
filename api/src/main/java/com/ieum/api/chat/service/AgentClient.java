package com.ieum.api.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.chat.dto.AvailableMcpServer;
import com.ieum.api.chat.dto.AvailableWebhook;
import com.ieum.api.chat.dto.ChatAgentRequest;
import com.ieum.api.chat.dto.ChatAgentResponse;
import com.ieum.api.chat.dto.ChatStreamEvent;
import com.ieum.api.chat.dto.IntegrationInfo;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

/**
 * ieum-agent {@code POST /v1/chat} 클라이언트.
 *
 * <h3>요청 헤더</h3>
 * <ul>
 *   <li>{@code X-LLM-Provider} — LLM 프로바이더 (OPENAI, CLAUDE, GEMINI)</li>
 *   <li>{@code X-LLM-Api-Key} — 복호화된 API Key</li>
 *   <li>{@code X-User-Id} — 사용자 UUID</li>
 *   <li>{@code X-Google-Access-Token} — Google 빌트인 도구 사용 시 (optional)</li>
 * </ul>
 *
 * <h3>요청 body</h3>
 * <pre>
 * {
 *   "prompt": "유저 자연어 입력",
 *   "currentNodes": [...],          ← null이면 신규 생성, 있으면 수정
 *   "currentEdges": [...],
 *   "availableIntegrations": [...],
 *   "unavailableIntegrations": [...]
 * }
 * </pre>
 */
@Slf4j
@Component
public class AgentClient {

    private final WebClient webClient;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper;

    public AgentClient(
        @Value("${ieum.agent.url}") String agentBaseUrl,
        @Value("${ieum.agent.chat-timeout-seconds:60}") int timeoutSeconds,
        ObjectMapper objectMapper
    ) {
        this.webClient = WebClient.builder()
            .baseUrl(agentBaseUrl)
            .build();
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
    }

    /**
     * AI 에이전트에 채팅 메시지를 전송하고 전체 응답을 블로킹으로 반환한다.
     *
     * @param prompt            사용자 자연어 입력
     * @param currentNodes      현재 캔버스 노드 목록 (null = 신규 생성)
     * @param currentEdges      현재 캔버스 엣지 목록
     * @param availableIntegrations   연동 완료된 서비스 목록
     * @param unavailableIntegrations 미연동 서비스 목록
     * @param llmProvider       LLM 프로바이더 이름 (예: "OPENAI", "CLAUDE")
     * @param apiKey            복호화된 API Key
     * @param googleAccessToken Google 빌트인 도구 사용 시 필요한 Access Token (nullable)
     * @param userId            현재 인증된 사용자 ID
     * @return AI 응답
     */
    public ChatAgentResponse chat(
        UUID workflowId,
        String prompt,
        List<Object> currentNodes,
        List<Object> currentEdges,
        List<IntegrationInfo> availableIntegrations,
        List<IntegrationInfo> unavailableIntegrations,
        String llmProvider,
        String apiKey,
        String googleAccessToken,
        String githubToken,
        String notionToken,
        List<AvailableMcpServer> availableMcpServers,
        List<AvailableWebhook> availableWebhooks,
        UUID userId,
        String userRole
    ) {
        log.debug("[AgentClient] chat 요청 — provider: {}, prompt: {}자",
            llmProvider, prompt != null ? prompt.length() : 0);

        ChatAgentRequest request = ChatAgentRequest.builder()
            .workflowId(workflowId != null ? workflowId.toString() : null)
            .prompt(prompt)
            .currentNodes(currentNodes)
            .currentEdges(currentEdges)
            .availableIntegrations(availableIntegrations != null ? availableIntegrations : List.of())
            .unavailableIntegrations(unavailableIntegrations != null ? unavailableIntegrations : List.of())
            .availableMcpServers(availableMcpServers != null ? availableMcpServers : List.of())
            .availableWebhooks(availableWebhooks != null ? availableWebhooks : List.of())
            .build();


        try {
            var requestSpec = webClient.post()
                .uri("/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-LLM-Provider", llmProvider)
                .header("X-LLM-Api-Key", apiKey)
                .header("X-User-Id", userId.toString());

            if (userRole != null) {
                requestSpec = requestSpec.header("X-User-Role", userRole);
            }
            if (googleAccessToken != null) {
                requestSpec = requestSpec.header("X-Google-Access-Token", googleAccessToken);
            }
            if (githubToken != null) {
                requestSpec = requestSpec.header("X-GitHub-Token", githubToken);
                log.debug("[AgentClient] X-GitHub-Token header injected");
            }
            if (notionToken != null) {
                requestSpec = requestSpec.header("X-Notion-Token", notionToken);
                log.debug("[AgentClient] X-Notion-Token header injected");
            }

            ChatAgentResponse response = requestSpec
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatAgentResponse.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

            if (response == null) {
                log.error("[AgentClient] 에이전트 응답이 null");
                throw new CustomException(ErrorCode.PROVIDER_ERROR);
            }

            log.debug("[AgentClient] chat 완료 — type: {}", response.getType());
            return response;

        } catch (CustomException e) {
            throw e;
        } catch (WebClientResponseException e) {
            log.error("[AgentClient] 에이전트 HTTP 오류 — status: {}, body: {}",
                e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 429) {
                throw new CustomException(ErrorCode.PROVIDER_RATE_LIMITED);
            }
            throw new CustomException(ErrorCode.PROVIDER_ERROR);
        } catch (Exception e) {
            if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                log.error("[AgentClient] 에이전트 응답 timeout ({}초)", timeoutSeconds);
                throw new CustomException(ErrorCode.PROVIDER_TIMEOUT);
            }
            log.error("[AgentClient] 에이전트 호출 실패", e);
            throw new CustomException(ErrorCode.PROVIDER_ERROR);
        }
    }

    /**
     * ieum-agent {@code /v1/chat/stream}(SSE)을 구독해 설계 진행 단계를 실시간으로 방출한다.
     *
     * <p>SSE 프레임을 {@link ChatStreamEvent}로 파싱한다: {@code stage} → 진행 단계,
     * {@code done} → 완성 응답(ChatAgentResponse), {@code error} → 오류.
     * HTTP/연결 오류는 ERROR 이벤트로 변환해 스트림을 정상 종료한다.
     */
    public Flux<ChatStreamEvent> chatStream(
        UUID workflowId,
        String prompt,
        List<Object> currentNodes,
        List<Object> currentEdges,
        List<IntegrationInfo> availableIntegrations,
        List<IntegrationInfo> unavailableIntegrations,
        String llmProvider,
        String apiKey,
        String googleAccessToken,
        String githubToken,
        String notionToken,
        List<AvailableMcpServer> availableMcpServers,
        List<AvailableWebhook> availableWebhooks,
        UUID userId,
        String userRole
    ) {
        log.debug("[AgentClient] chatStream(SSE) 요청 — provider: {}, prompt: {}자",
            llmProvider, prompt != null ? prompt.length() : 0);

        ChatAgentRequest request = ChatAgentRequest.builder()
            .workflowId(workflowId != null ? workflowId.toString() : null)
            .prompt(prompt)
            .currentNodes(currentNodes)
            .currentEdges(currentEdges)
            .availableIntegrations(availableIntegrations != null ? availableIntegrations : List.of())
            .unavailableIntegrations(unavailableIntegrations != null ? unavailableIntegrations : List.of())
            .availableMcpServers(availableMcpServers != null ? availableMcpServers : List.of())
            .availableWebhooks(availableWebhooks != null ? availableWebhooks : List.of())
            .build();

        WebClient.RequestBodySpec spec = webClient.post()
            .uri("/v1/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .header("X-LLM-Provider", llmProvider)
            .header("X-LLM-Api-Key", apiKey)
            .header("X-User-Id", userId.toString());

        if (userRole != null) {
            spec = spec.header("X-User-Role", userRole);
        }
        if (googleAccessToken != null) {
            spec = spec.header("X-Google-Access-Token", googleAccessToken);
        }
        if (githubToken != null) {
            spec = spec.header("X-GitHub-Token", githubToken);
        }
        if (notionToken != null) {
            spec = spec.header("X-Notion-Token", notionToken);
        }

        return spec.bodyValue(request)
            .retrieve()
            .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
            .mapNotNull(this::toStreamEvent)
            .onErrorResume(this::toErrorEvent);
    }

    /** SSE 프레임을 ChatStreamEvent로 파싱한다. 알 수 없는 event/파싱 실패는 null로 건너뛴다. */
    private ChatStreamEvent toStreamEvent(ServerSentEvent<String> sse) {
        String event = sse.event();
        String data = sse.data();
        if (event == null || data == null) {
            return null;
        }
        try {
            return switch (event) {
                case "stage" -> ChatStreamEvent.stage(
                    objectMapper.readTree(data).path("stage").asText());
                case "done" -> ChatStreamEvent.done(
                    objectMapper.readValue(data, ChatAgentResponse.class));
                case "error" -> ChatStreamEvent.error(
                    objectMapper.readTree(data).path("message").asText("AI 응답 중 오류가 발생했습니다."));
                default -> null;
            };
        } catch (Exception e) {
            // null 반환 시 mapNotNull로 유실된다. done 프레임 파싱 실패 시 finalizeStream이
            // 아예 실행되지 않는 silent failure를 막기 위해 error 이벤트로 변환해 구독자에게 알린다.
            log.error("[AgentClient] SSE 이벤트 파싱 실패 — event: {}, data: {}", event, data, e);
            return ChatStreamEvent.error("AI 응답 데이터를 처리하는 중 오류가 발생했습니다.");
        }
    }

    /** HTTP/연결 오류를 ERROR 이벤트로 변환해 스트림을 정상 종료한다. */
    private Flux<ChatStreamEvent> toErrorEvent(Throwable e) {
        if (e instanceof WebClientResponseException we && we.getStatusCode().value() == 429) {
            log.warn("[AgentClient] chatStream 레이트리밋(429)");
            return Flux.just(ChatStreamEvent.error("요청이 많아 잠시 후 다시 시도해주세요."));
        }
        log.error("[AgentClient] chatStream SSE 오류", e);
        return Flux.just(ChatStreamEvent.error("AI 응답 중 오류가 발생했습니다."));
    }
}
