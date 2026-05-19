package com.ieum.api.chat.handler;

import com.ieum.api.chat.dto.ChatRequest;
import com.ieum.api.chat.dto.ChatStreamResponse;
import com.ieum.api.chat.service.AgentClient;
import com.ieum.api.chat.service.ChatService;
import com.ieum.api.chat.service.ChatService.StreamSetupResult;
import com.ieum.common.exception.CustomException;
import java.security.Principal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Controller;

/**
 * WebSocket STOMP 채팅 핸들러.
 *
 * <h3>흐름</h3>
 * <ol>
 *   <li>클라이언트 → {@code /app/chat/{workflowId}} 메시지 전송 (ChatRequest)</li>
 *   <li>세션 생성/조회, 사용자 메시지 저장, 에이전트 설정 로드 (트랜잭션)</li>
 *   <li>ieum-agent SSE 스트리밍 구독 시작</li>
 *   <li>토큰 수신마다 {@code /user/queue/chat/stream} 으로 즉시 전송</li>
 *   <li>완료 시 AGENT 메시지 DB 저장 + COMPLETE 프레임 전송</li>
 *   <li>오류 시 ERROR 프레임 전송</li>
 * </ol>
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
        String userName = principal.getName();  // userId.toString()
        UUID userId = UUID.fromString(userName);

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

        // 2. 스트리밍 구독 (비동기)
        StringBuilder fullContent = new StringBuilder();
        AtomicInteger tokenIndex = new AtomicInteger(0);

        agentClient.chatStream(
            setup.messages(),
            setup.config().llmProvider(),
            setup.config().decryptedApiKey(),
            setup.googleToken(),
            userId
        ).subscribe(
            token -> {
                fullContent.append(token);
                sendToUser(userName,
                    ChatStreamResponse.token(token, tokenIndex.getAndIncrement()));
            },
            error -> {
                log.error("[WS] 스트리밍 오류 — sessionId: {}", setup.sessionId(), error);
                String errMsg = error instanceof CustomException ce
                    ? ce.getMessage()
                    : "AI 응답 중 오류가 발생했습니다.";
                sendToUser(userName, ChatStreamResponse.error(errMsg));
            },
            () -> {
                String content = fullContent.toString();
                log.info("[WS] 스트리밍 완료 — sessionId: {}, tokens: {}",
                    setup.sessionId(), tokenIndex.get());

                try {
                    chatService.saveAgentMessageById(
                        setup.sessionId(), content, null, tokenIndex.get());
                } catch (Exception e) {
                    log.error("[WS] AGENT 메시지 저장 실패 — sessionId: {}", setup.sessionId(), e);
                }

                sendToUser(userName, ChatStreamResponse.complete(tokenIndex.get()));
            }
        );
    }

    private void sendToUser(String userName, ChatStreamResponse response) {
        messagingTemplate.convertAndSendToUser(userName, USER_STREAM_DESTINATION, response);
    }
}
