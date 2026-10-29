package com.ieum.api.chat.handler;

import com.ieum.api.chat.dto.ChatRequest;
import com.ieum.api.chat.dto.ChatResponse;
import com.ieum.api.chat.dto.ChatStreamEvent;
import com.ieum.api.chat.dto.ChatStreamResponse;
import com.ieum.api.chat.service.AgentClient;
import com.ieum.api.chat.service.AgentClient.AgentChatCallParams;
import com.ieum.api.chat.service.ChatService;
import com.ieum.api.chat.service.ChatService.StreamSetupResult;
import com.ieum.api.chat.service.ChatTurnLock;
import com.ieum.auth.delegation.DelegationTokenService;
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
    static final String TURN_BUSY_MESSAGE = "이전 응답을 기다리는 중입니다. 잠시 후 다시 보내 주세요.";

    private final ChatService chatService;
    private final AgentClient agentClient;
    private final SimpMessageSendingOperations messagingTemplate;
    private final ChatTurnLock chatTurnLock;
    private final DelegationTokenService delegationTokenService;

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

        // 0. 사용자당 동시 턴 1개(IEUM-BE-106) — 세션·메시지를 만들기 전에 막는다(바쁠 때 보낸 메시지가 대화에 남지 않게).
        boolean acquired;
        try {
            acquired = chatTurnLock.tryAcquire(userId);
        } catch (Exception e) {
            log.error("[WS] 동시 턴 락 확인 실패 — userId: {}", userId, e);
            sendToUser(userName, ChatStreamResponse.error("서버 오류가 발생했습니다."));
            return;
        }
        if (!acquired) {
            sendToUser(userName, ChatStreamResponse.error(TURN_BUSY_MESSAGE));
            return;
        }

        // 스트림에 넘기기 전에 어디서 끝나든 락을 푼다 — 넘긴 뒤엔 스트림 doFinally가 위임 토큰과 함께 푼다.
        boolean handedOff = false;
        try {
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
            //    쿼터 초과(CustomException)는 예외가 그대로 전파되며 BetaQuotaService가 스스로 카운터를 원복하므로
            //    별도 환불이 불필요하다. Redis 다운 등 infra RuntimeException도 마찬가지로 INCR 자체가 실패했을
            //    것이므로(키를 반환받지 못했으므로) 환불할 예약이 없다 — 두 경우 모두 여기서 잡아 error 프레임을
            //    보내지 않으면 클라이언트가 @MessageMapping 밖으로 탈출한 예외 때문에 응답을 영영 못 받고 멈춘다.
            String betaReservationKey;
            try {
                betaReservationKey = chatService.reserveBetaQuota(setup.config(), userId);
            } catch (CustomException e) {
                log.warn("[WS] 베타 쿼터 예약 실패 — userId: {}, error: {}", userId, e.getMessage());
                sendToUser(userName, ChatStreamResponse.error(e.getMessage()));
                return;
            } catch (Exception e) {
                log.error("[WS] 베타 쿼터 예약 예외 — userId: {}", userId, e);
                sendToUser(userName, ChatStreamResponse.error("서버 오류가 발생했습니다."));
                return;
            }

            // 3. 위임 토큰(IEUM-BE-106) — agent가 이 턴 동안 사용자 대신 BE 허용 경로 5개를 부른다. 실패하면 예약을 돌려준다.
            //    role은 Principal authority 문자열 그대로(ROLE_ 접두사 포함) — 필터가 SimpleGrantedAuthority에 그대로 넣는다.
            if (userRole == null) {
                log.error("[WS] Principal에 role이 없어 위임 토큰을 발급하지 않는다 — userId: {}", userId);
                chatService.releaseBetaQuotaOnFailure(betaReservationKey);
                sendToUser(userName, ChatStreamResponse.error("서버 오류가 발생했습니다."));
                return;
            }
            String delegationToken;
            try {
                delegationToken = delegationTokenService.issue(userId, userRole, workflowId);
            } catch (Exception e) {
                log.error("[WS] 위임 토큰 발급 실패 — userId: {}", userId, e);
                chatService.releaseBetaQuotaOnFailure(betaReservationKey);
                sendToUser(userName, ChatStreamResponse.error("서버 오류가 발생했습니다."));
                return;
            }

            // 4. 스트리밍 구독 (비동기) — agent SSE 이벤트를 STAGE/DONE/ERROR로 분기 전달.
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
                .delegationToken(delegationToken)
                .build());

            withBetaQuotaRefund(chatStream, betaReservationKey)
                // finalizeStream은 동기 blocking(JPA) 작업이므로 Netty EventLoop 스레드에서 실행되면
                // Thread Starvation을 유발한다. boundedElastic로 전환해 별도 스레드 풀에서 처리한다.
                .publishOn(Schedulers.boundedElastic())
                // publishOn 뒤라 DONE 저장(finalizeStream)이 끝난 다음에 돈다 — 저장 중에 다음 턴이 시작되지 않는다.
                .doFinally(signal -> endTurn(userId, delegationToken))
                .subscribe(
                    event -> handleStreamEvent(userName, workflowId, request, setup, event),
                    error -> {
                        log.error("[WS] 스트리밍 오류 — sessionId: {}", setup.sessionId(), error);
                        sendToUser(userName, ChatStreamResponse.error("AI 응답 중 오류가 발생했습니다."));
                    }
                );
            handedOff = true;
        } finally {
            if (!handedOff) {
                try {
                    chatTurnLock.release(userId);
                } catch (Exception e) {
                    log.warn("[WS] 동시 턴 락 해제 실패(TTL로 만료) — userId: {}", userId, e);
                }
            }
        }
    }

    /** 턴 종료(완료·오류·취소) — 위임 토큰을 바로 폐기하고 동시 턴 락을 푼다. 하나가 실패해도 다른 하나는 한다. */
    private void endTurn(UUID userId, String delegationToken) {
        try {
            delegationTokenService.revoke(delegationToken);
        } catch (Exception e) {
            log.warn("[WS] 위임 토큰 폐기 실패(TTL로 만료) — userId: {}", userId, e);
        }
        try {
            chatTurnLock.release(userId);
        } catch (Exception e) {
            log.warn("[WS] 동시 턴 락 해제 실패(TTL로 만료) — userId: {}", userId, e);
        }
    }

    /**
     * 베타 platform 키 일일 카운터를 정확히 1회 환불하도록 스트림에 감시 로직을 덧씌운다.
     *
     * <p>DONE(agent 호출 자체의 성공)을 수신했을 때만 {@code succeeded=true}로 표시하고,
     * {@code doFinally}에서 그렇지 않은 모든 종료 시그널(error/cancel(클라 disconnect)/
     * ERROR 이벤트 후 정상 종료 포함)에 대해 환불한다. 패키지 전용으로 노출해 StepVerifier로
     * cancel/error 등 리액티브 종료 시그널별 exactly-once 환불을 직접 검증할 수 있게 한다.
     *
     * @param betaReservationKey reserveBetaQuota가 반환한 일일 카운터 키. null이면 베타 미적용이라
     *     환불 대상 자체가 없다(감시는 걸되 doFinally에서 항상 no-op).
     */
    Flux<ChatStreamEvent> withBetaQuotaRefund(Flux<ChatStreamEvent> source, String betaReservationKey) {
        AtomicBoolean succeeded = new AtomicBoolean(false);
        return source
            .doOnNext(event -> {
                if (event.type() == ChatStreamEvent.EventType.DONE) {
                    succeeded.set(true);
                }
            })
            .doFinally(signal -> {
                if (betaReservationKey != null && !succeeded.get()) {
                    chatService.releaseBetaQuotaOnFailure(betaReservationKey);
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
                } catch (CustomException e) {
                    // 저장 거부(예: 노드 config에 웹훅 URL 원문 — IEUM-BE-62)는 사용자가 다음 행동을
                    // 알아야 풀 수 있다. prepareStream 실패와 같은 방식으로 메시지를 그대로 전달한다.
                    // CustomException 메시지가 되싣는 값은 NodeDerivation.echo(제어·서식·줄구분 문자 치환, 64자)를 거친 것뿐이다.
                    log.warn("[WS] done 처리(저장) 거부 — sessionId: {}, error: {}",
                        setup.sessionId(), e.getMessage());
                    sendToUser(userName, ChatStreamResponse.error(e.getMessage()));
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
