package com.ieum.api.chat.service;

import com.ieum.api.chat.dto.AgentMessage;
import com.ieum.api.chat.dto.ChatAgentRequest;
import com.ieum.api.chat.dto.ChatAgentResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

/**
 * ieum-agent 채팅 엔드포인트({@code POST /v1/chat}) 클라이언트.
 *
 * <p>REST 블로킹 호출({@link #chat})과 SSE 스트리밍 호출({@link #chatStream}) 두 가지를 제공한다.
 * - REST 엔드포인트에서는 {@code chat()}을 사용한다.
 * - WebSocket 실시간 스트리밍에서는 {@code chatStream()}을 사용한다.
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
     * @param messages           대화 히스토리 (최신 사용자 메시지 포함)
     * @param llmProvider        LLM 프로바이더 이름 (예: "CLAUDE", "OPENAI")
     * @param apiKey             복호화된 API Key
     * @param googleAccessToken  Google 빌트인 도구 사용 시 필요한 Access Token (nullable)
     * @return AI 응답 (content + 토큰 사용량)
     */
    public ChatAgentResponse chat(
        List<AgentMessage> messages,
        String llmProvider,
        String apiKey,
        String googleAccessToken
    ) {
        log.debug("[AgentClient] chat 요청 — provider: {}, messages: {}개", llmProvider, messages.size());

        ChatAgentRequest request = ChatAgentRequest.builder()
            .messages(messages)
            .stream(false)
            .build();

        try {
            var requestSpec = webClient.post()
                .uri("/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-LLM-Provider", llmProvider)
                .header("X-LLM-Api-Key", apiKey);

            if (googleAccessToken != null) {
                requestSpec = requestSpec.header("X-Google-Access-Token", googleAccessToken);
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

            log.debug("[AgentClient] chat 완료 — inputTokens: {}, outputTokens: {}",
                response.getInputTokens(), response.getOutputTokens());
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
     * AI 에이전트에 채팅 메시지를 전송하고 SSE 스트리밍으로 토큰 조각을 반환한다.
     *
     * <p>WebSocket 브로드캐스트에 사용된다.
     * 에러 발생 시 {@link Flux#error}로 전파한다.
     *
     * @return 토큰 조각 문자열의 Flux
     */
    public Flux<String> chatStream(
        List<AgentMessage> messages,
        String llmProvider,
        String apiKey,
        String googleAccessToken
    ) {
        log.debug("[AgentClient] chatStream 요청 — provider: {}, messages: {}개",
            llmProvider, messages.size());

        ChatAgentRequest request = ChatAgentRequest.builder()
            .messages(messages)
            .stream(true)
            .build();

        var requestSpec = webClient.post()
            .uri("/v1/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-LLM-Provider", llmProvider)
            .header("X-LLM-Api-Key", apiKey);

        if (googleAccessToken != null) {
            requestSpec = requestSpec.header("X-Google-Access-Token", googleAccessToken);
        }

        return requestSpec
            .bodyValue(request)
            .retrieve()
            .bodyToFlux(String.class)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .doOnComplete(() -> log.debug("[AgentClient] chatStream 완료"))
            .doOnError(e -> log.error("[AgentClient] chatStream 오류", e));
    }
}
