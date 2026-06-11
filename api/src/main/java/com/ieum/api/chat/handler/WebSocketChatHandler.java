package com.ieum.api.chat.handler;

import com.ieum.api.chat.dto.ChatRequest;
import com.ieum.api.chat.dto.ChatResponse;
import com.ieum.api.chat.dto.ChatStreamEvent;
import com.ieum.api.chat.dto.ChatStreamResponse;
import com.ieum.api.chat.service.AgentClient;
import com.ieum.api.chat.service.ChatService;
import com.ieum.api.chat.service.ChatService.StreamSetupResult;
import com.ieum.common.exception.CustomException;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
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
            setup = chatService.prepareStream(workflowId, userId, request);
        } catch (CustomException e) {
            log.warn("[WS] prepareStream 실패 — userId: {}, error: {}", userId, e.getMessage());
            sendToUser(userName, ChatStreamResponse.error(e.getMessage()));
            return;
        } catch (Exception e) {
            log.error("[WS] prepareStream 예외 — userId: {}", userId, e);
            sendToUser(userName, ChatStreamResponse.error("서버 오류가 발생했습니다."));
            return;
        }

        // 2. 스트리밍 구독 (비동기) — agent SSE 이벤트를 STAGE/DONE/ERROR로 분기 전달
        agentClient.chatStream(
            workflowId,
            setup.prompt(),
            setup.currentNodes(),
            setup.currentEdges(),
            setup.integrationContext().available(),
            setup.integrationContext().unavailable(),
            setup.config().llmProvider(),
            setup.config().decryptedApiKey(),
            setup.googleToken(),
            setup.githubToken(),
            setup.notionToken(),
            setup.availableMcpServers(),
            setup.availableWebhooks(),
            userId,
            userRole
        )
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
     * agent SSE 이벤트를 사용자 큐로 분기 전달한다.
     * DONE 수신 시 워크플로우/메시지를 저장({@code finalizeStream})하고 최종 응답을 전달한다.
     */
    private void handleStreamEvent(String userName, UUID workflowId, ChatRequest request,
            StreamSetupResult setup, ChatStreamEvent event) {
        switch (event.type()) {
            case STAGE -> sendToUser(userName, ChatStreamResponse.stage(event.stage()));
            case DONE -> {
                try {
                    ChatResponse response = chatService.finalizeStream(
                        workflowId,
                        setup.sessionId(),
                        event.response(),
                        setup.config(),
                        request.getCredentialId()
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
