package com.ieum.api.chat.handler;

import com.ieum.api.chat.dto.ChatRequest;
import com.ieum.api.chat.dto.ChatResponse;
import com.ieum.api.chat.dto.ChatStreamEvent;
import com.ieum.api.chat.dto.ChatStreamResponse;
import com.ieum.api.chat.service.AgentClient;
import com.ieum.api.chat.service.AgentClient.AgentChatCallParams;
import com.ieum.api.chat.service.ChatService;
import com.ieum.api.chat.service.ChatService.StreamSetupResult;
import com.ieum.common.exception.CustomException;
import java.security.Principal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * WebSocket STOMP 채팅 핸들러.
 *
 * <h3>흐름</h3>
 * <ol>
 *   <li>클라이언트 → {@code /app/chat/{workflowId}} 메시지 전송 (ChatRequest)</li>
 *   <li>세션 생성/조회, 사용자 메시지 저장, 에이전트 설정 로드 (트랜잭션)</li>
 *   <li>{@link AgentClient#chatStream} 구독 시작</li>
 *   <li>응답 수신 후 {@code /user/queue/chat/stream} 으로 전송</li>
 *   <li>완료 시 AGENT 메시지 DB 저장 + COMPLETE 프레임 전송</li>
 *   <li>오류 시 ERROR 프레임 전송</li>
 * </ol>
 *
 * <p><b>현재 스트리밍 동작 방식:</b> {@code AgentClient.chatStream()}은 ieum-agent가
 * 스트리밍을 지원하지 않아 응답 전체를 단일 청크로 방출한다.
 * 클라이언트는 TOKEN 1건 → COMPLETE 순서로 수신하게 된다.
 * ieum-agent가 SSE를 지원하면 {@link AgentClient#chatStream} 구현만 교체하면 된다.
 *
 * <h3>클라이언트 구독 경로</h3>
 * {@code /user/queue/chat/stream} — 개인 큐 (다른 사용자에게 노출 없음)
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class WebSocketChatHandler {

    private static final String USER_STREAM_DESTINATION = "/queue/chat/stream";

    private final ChatService chatService;
    private final AgentClient agentClient;
    private final SimpMessageSendingOperations messagingTemplate;

    /**
     * 채팅 메시지를 수신하고 AI 응답을 스트리밍으로 전송한다.
     *
     * @param workflowId 워크플로우 ID (경로 변수)
     * @param request    ChatRequest (message + optional sessionId)
     * @param principal  인증된 사용자 (name = userId)
     */
    @MessageMapping("/chat/{workflowId}")
    public void handleChat(
        @DestinationVariable UUID workflowId,
        @Payload ChatRequest request,
        Principal principal
    ) {
        if (principal == null) {
            log.warn("[WS] 인증 정보(principal)가 없어 메시지를 처리할 수 없습니다 — workflowId: {}", workflowId);
            return;
        }
        String userName = principal.getName();  // userId.toString()
        UUID userId = UUID.fromString(userName);
        // WebSocketAuthInterceptor가 setUser(Authentication)로 role을 authorities에 담아둔다.
        String userRole = (principal instanceof Authentication auth && !auth.getAuthorities().isEmpty())
            ? auth.getAuthorities().iterator().next().getAuthority()
            : null;

        log.info("[WS] 채팅 메시지 수신 — workflowId: {}, userId: {}", workflowId, userId);

        // 1. 트랜잭션 처리 (세션/메시지 저장, 에이전트 설정 로드)
        StreamSetupResult setup;
        try {
            setup = chatService.prepareStream(workflowId, userId, userRole, request);
        } catch (CustomException e) {
            log.warn("[WS] prepareStream 실패 — userId: {}, error: {}", userId, e.getMessage());
            sendToUser(userName, ChatStreamResponse.error(e.getMessage()));
            return;
        } catch (Exception e) {
            log.error("[WS] prepareStream 예외 — userId: {}", userId, e);
            sendToUser(userName, ChatStreamResponse.error("서버 오류가 발생했습니다."));
            return;
        }

        // 2. 베타 platform 키 쿼터 예약 — subscribe 바로 직전(사전작업은 이미 끝났으므로 그 앞 실패는 환불 대상이 아니다).
        //    쿼터 초과 시 예외가 그대로 전파되며 BetaQuotaService가 스스로 카운터를 원복하므로 별도 환불 불필요.
        try {
            chatService.reserveBetaQuota(setup.config(), userId);
        } catch (CustomException e) {
            log.warn("[WS] 베타 쿼터 예약 실패 — userId: {}, error: {}", userId, e.getMessage());
            sendToUser(userName, ChatStreamResponse.error(e.getMessage()));
            return;
        }

        // 3. 스트리밍 구독 (비동기) — agent SSE 이벤트를 STAGE/DONE/ERROR로 분기 전달.
        //    withBetaQuotaRefund가 doFinally로 error/cancel(클라 disconnect)/비성공-complete를
        //    전부 잡아 정확히 1회 환불한다(§#1~3 강건화).
        Flux<ChatStreamEvent> chatStream = agentClient.chatStream(AgentChatCallParams.builder()
            .workflowId(workflowId)
            .prompt(setup.prompt())
            .currentNodes(setup.currentNodes())
            .currentEdges(setup.currentEdges())
            .availableIntegrations(setup.integrationContext().available())
            .unavailableIntegrations(setup.integrationContext().unavailable())
            .llmProvider(setup.config().llmProvider())
            .apiKey(setup.config().decryptedApiKey())
            .googleAccessToken(setup.googleToken())
            .githubToken(setup.githubToken())
            .notionToken(setup.notionToken())
            .availableMcpServers(setup.availableMcpServers())
            .availableWebhooks(setup.availableWebhooks())
            .userId(userId)
            .userRole(userRole)
            .useBetaPlatformKey(setup.config().useBetaPlatformKey())
            .build());

        withBetaQuotaRefund(chatStream, setup.config(), userId)
            // finalizeStream은 동기 blocking(JPA) 작업이므로 Netty EventLoop 스레드에서 실행되면
            // Thread Starvation을 유발한다. boundedElastic로 전환해 별도 스레드 풀에서 처리한다.
            .publishOn(Schedulers.boundedElastic())
            .subscribe(
                event -> handleStreamEvent(userName, workflowId, request, setup, event),
                error -> {
                    log.error("[WS] 스트리밍 오류 — sessionId: {}", setup.sessionId(), error);
                    sendToUser(userName, ChatStreamResponse.error("AI 응답 중 오류가 발생했습니다."));
                }
            );
    }

    /**
     * 베타 platform 키 일일 카운터를 정확히 1회 환불하도록 스트림에 감시 로직을 덧씌운다.
     *
     * <p>DONE(agent 호출 자체의 성공)을 수신했을 때만 {@code succeeded=true}로 표시하고,
     * {@code doFinally}에서 그렇지 않은 모든 종료 시그널(error/cancel(클라 disconnect)/
     * ERROR 이벤트 후 정상 종료 포함)에 대해 환불한다. 패키지 전용으로 노출해 StepVerifier로
     * cancel/error 등 리액티브 종료 시그널별 exactly-once 환불을 직접 검증할 수 있게 한다.
     */
    Flux<ChatStreamEvent> withBetaQuotaRefund(Flux<ChatStreamEvent> source, ChatService.AgentConfig config, UUID userId) {
        AtomicBoolean succeeded = new AtomicBoolean(false);
        return source
            .doOnNext(event -> {
                if (event.type() == ChatStreamEvent.EventType.DONE) {
                    succeeded.set(true);
                }
            })
            .doFinally(signal -> {
                if (!succeeded.get()) {
                    chatService.releaseBetaQuotaOnFailure(config, userId);
                }
            });
    }

    /**
     * agent SSE 이벤트를 사용자 큐로 분기 전달한다.
     * DONE 수신 시 워크플로우/메시지를 저장({@code finalizeStream})하고 최종 응답을 전달한다.
     */
    private void handleStreamEvent(String userName, UUID workflowId, ChatRequest request,
            StreamSetupResult setup, ChatStreamEvent event) {
        switch (event.type()) {
            case STAGE -> sendToUser(userName, ChatStreamResponse.stage(event.stage()));
            case DONE -> {
                try {
                    // userName == userId.toString() (handleChat 진입부 참고) — 별도 파라미터 없이 복원한다.
                    ChatResponse response = chatService.finalizeStream(
                        workflowId,
                        setup.sessionId(),
                        event.response(),
                        setup.config(),
                        request.getCredentialId(),
                        UUID.fromString(userName)
                    );
                    sendToUser(userName, ChatStreamResponse.done(response));
                } catch (Exception e) {
                    log.error("[WS] done 처리(저장) 실패 — sessionId: {}", setup.sessionId(), e);
                    sendToUser(userName, ChatStreamResponse.error("응답 저장 중 오류가 발생했습니다."));
                }
            }
            case ERROR -> sendToUser(userName, ChatStreamResponse.error(event.errorMessage()));
        }
    }

    private void sendToUser(String userName, ChatStreamResponse response) {
        messagingTemplate.convertAndSendToUser(userName, USER_STREAM_DESTINATION, response);
    }
}
