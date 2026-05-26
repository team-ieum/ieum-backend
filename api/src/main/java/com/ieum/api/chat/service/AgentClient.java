package com.ieum.api.chat.service;

import com.ieum.api.chat.dto.ChatAgentRequest;
import com.ieum.api.chat.dto.ChatAgentResponse;
import com.ieum.api.chat.dto.IntegrationInfo;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

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

    public AgentClient(
        @Value("${ieum.agent.url}") String agentBaseUrl,
        @Value("${ieum.agent.chat-timeout-seconds:60}") int timeoutSeconds
    ) {
        this.webClient = WebClient.builder()
            .baseUrl(agentBaseUrl)
            .build();
        this.timeoutSeconds = timeoutSeconds;
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
        String prompt,
        List<Object> currentNodes,
        List<Object> currentEdges,
        List<IntegrationInfo> availableIntegrations,
        List<IntegrationInfo> unavailableIntegrations,
        String llmProvider,
        String apiKey,
        String googleAccessToken,
        String githubToken,
        UUID userId
    ) {
        log.debug("[AgentClient] chat 요청 — provider: {}, prompt: {}자",
            llmProvider, prompt != null ? prompt.length() : 0);

        ChatAgentRequest request = ChatAgentRequest.builder()
            .prompt(prompt)
            .currentNodes(currentNodes)
            .currentEdges(currentEdges)
            .availableIntegrations(availableIntegrations != null ? availableIntegrations : List.of())
            .unavailableIntegrations(unavailableIntegrations != null ? unavailableIntegrations : List.of())
            .build();


        try {
            var requestSpec = webClient.post()
                .uri("/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-LLM-Provider", llmProvider)
                .header("X-LLM-Api-Key", apiKey)
                .header("X-User-Id", userId.toString());

            if (googleAccessToken != null) {
                requestSpec = requestSpec.header("X-Google-Access-Token", googleAccessToken);
            }
            if (githubToken != null) {
                requestSpec = requestSpec.header("X-GitHub-Token", githubToken);
                log.debug("[AgentClient] X-GitHub-Token header injected");
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
     * ieum-agent {@code /v1/chat}은 스트리밍을 지원하지 않으므로,
     * blocking 호출 결과를 단일 항목의 Flux로 래핑한다.
     *
     * <p><b>주의:</b> 실제 토큰 단위 스트리밍이 아니다.
     * AI 응답 전체가 완성된 후 한 번에 단일 항목({@code onNext} 1회)으로 방출된다.
     * 따라서 구독자는 긴 대기 후 전체 응답을 한꺼번에 수신하게 된다.
     *
     * <p>ieum-agent가 SSE/스트리밍 엔드포인트를 제공하게 되면
     * 이 메서드를 교체하면 호출부 변경 없이 진짜 스트리밍으로 전환할 수 있다.
     */
    public Flux<String> chatStream(
        String prompt,
        List<Object> currentNodes,
        List<Object> currentEdges,
        List<IntegrationInfo> availableIntegrations,
        List<IntegrationInfo> unavailableIntegrations,
        String llmProvider,
        String apiKey,
        String googleAccessToken,
        String githubToken,
        UUID userId
    ) {
        log.debug("[AgentClient] chatStream 요청 — provider: {}, prompt: {}자",
            llmProvider, prompt != null ? prompt.length() : 0);

        return Flux.<String>create(sink -> {
            try {
                String content = chat(
                    prompt, currentNodes, currentEdges,
                    availableIntegrations, unavailableIntegrations,
                    llmProvider, apiKey, googleAccessToken, githubToken, userId
                ).getContent();
                sink.next(content);
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
