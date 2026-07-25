package com.ieum.api.chat.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.ieum.api.chat.dto.ChatAgentResponse;
import com.ieum.api.chat.dto.ChatRequest;
import com.ieum.api.chat.dto.ChatResponse;
import com.ieum.api.chat.dto.ChatStreamEvent;
import com.ieum.api.chat.dto.ChatStreamResponse;
import com.ieum.api.chat.dto.ChatStreamResponse.StreamType;
import com.ieum.api.chat.service.AgentClient;
import com.ieum.api.chat.service.AgentClient.AgentChatCallParams;
import com.ieum.api.chat.service.ChatService;
import com.ieum.api.chat.service.ChatService.AgentConfig;
import com.ieum.api.chat.service.ChatService.StreamSetupResult;
import com.ieum.api.chat.service.IntegrationContextService.IntegrationContext;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class WebSocketChatHandlerTest {

    private static final String DESTINATION = "/queue/chat/stream";

    @Mock private ChatService chatService;
    @Mock private AgentClient agentClient;
    @Mock private SimpMessageSendingOperations messagingTemplate;

    @InjectMocks private WebSocketChatHandler handler;

    private UUID workflowId;
    private UUID userId;
    private UUID sessionId;
    private Principal principal;
    private ChatRequest request;
    private StreamSetupResult setup;

    @BeforeEach
    void setUp() {
        workflowId = UUID.randomUUID();
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        principal = new UsernamePasswordAuthenticationToken(
            userId.toString(), null,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));
        request = mock(ChatRequest.class);

        setup = new StreamSetupResult(
            sessionId,
            new AgentConfig("CLAUDE", "key", null, false),
            "워크플로우 만들어줘",
            null, null,
            new IntegrationContext(List.of(), List.of()),
            null, null, null,
            List.of(), List.of()
        );
    }

    /** handleChat() 전체 흐름을 도는 테스트에서만 필요 — withBetaQuotaRefund 단위 테스트는 handleChat을 거치지 않는다. */
    private void mockPrepareStream() {
        given(chatService.prepareStream(eq(workflowId), eq(userId), eq("ROLE_USER"), eq(request))).willReturn(setup);
    }

    private void mockChatStream(ChatStreamEvent... events) {
        given(agentClient.chatStream(any(AgentChatCallParams.class))).willReturn(Flux.just(events));
    }

    private List<ChatStreamResponse> captureSent(int times) {
        // 구독이 boundedElastic 스레드에서 비동기 처리되므로 timeout 검증으로 완료를 대기한다.
        ArgumentCaptor<ChatStreamResponse> captor = ArgumentCaptor.forClass(ChatStreamResponse.class);
        verify(messagingTemplate, timeout(2000).times(times))
            .convertAndSendToUser(eq(userId.toString()), eq(DESTINATION), captor.capture());
        return captor.getAllValues();
    }

    // ─────────────────── handleChat (전체 흐름) ─────────────────────────────

    @Test
    @DisplayName("stage → done 흐름: 진행 프레임 전달 후 done 시 finalizeStream으로 저장하고 최종 프레임 전달")
    void stage_then_done() {
        mockPrepareStream();
        ChatAgentResponse agentResponse = mock(ChatAgentResponse.class);
        ChatResponse finalResponse = mock(ChatResponse.class);
        given(chatService.finalizeStream(eq(workflowId), eq(sessionId), eq(agentResponse), any(), any(), any()))
            .willReturn(finalResponse);

        mockChatStream(
            ChatStreamEvent.stage("designing"),
            ChatStreamEvent.stage("reviewing"),
            ChatStreamEvent.done(agentResponse)
        );

        handler.handleChat(workflowId, request, principal);

        // 3개 프레임 전송 완료까지 대기(done 프레임은 finalizeStream 후 전송됨)한 뒤 검증한다.
        List<ChatStreamResponse> sent = captureSent(3);
        verify(chatService).finalizeStream(eq(workflowId), eq(sessionId), eq(agentResponse), any(), any(), any());
        assertThat(sent.get(0).getType()).isEqualTo(StreamType.STAGE);
        assertThat(sent.get(0).getStage()).isEqualTo("designing");
        assertThat(sent.get(1).getType()).isEqualTo(StreamType.STAGE);
        assertThat(sent.get(1).getStage()).isEqualTo("reviewing");
        assertThat(sent.get(2).getType()).isEqualTo(StreamType.DONE);
        assertThat(sent.get(2).getData()).isEqualTo(finalResponse);
        // 성공(DONE) 수신 — 베타 쿼터 환불 대상 아님
        verify(chatService, after(300).never()).releaseBetaQuotaOnFailure(any(), any());
    }

    @Test
    @DisplayName("error 이벤트: ERROR 프레임을 전달하고 저장은 하지 않는다")
    void error_event() {
        mockPrepareStream();
        mockChatStream(ChatStreamEvent.error("AI 응답 중 오류가 발생했습니다."));

        handler.handleChat(workflowId, request, principal);

        List<ChatStreamResponse> sent = captureSent(1);
        assertThat(sent.get(0).getType()).isEqualTo(StreamType.ERROR);
        assertThat(sent.get(0).getContent()).isEqualTo("AI 응답 중 오류가 발생했습니다.");
        verify(chatService, after(300).never())
            .finalizeStream(any(), any(), any(), any(), any(), any());
        // ERROR 이벤트 후 스트림이 정상 종료돼도 DONE을 받지 못했으므로 withBetaQuotaRefund의 doFinally가 환불한다.
        verify(chatService).releaseBetaQuotaOnFailure(eq(setup.config()), eq(userId));
    }

    @Test
    @DisplayName("베타 쿼터 예약 실패 시 ERROR 프레임만 전달하고 chatStream을 구독하지 않는다")
    void reserveBetaQuota_throws_doesNotSubscribeToChatStream() {
        mockPrepareStream();
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.BETA_QUOTA_EXCEEDED))
            .when(chatService).reserveBetaQuota(setup.config(), userId);

        handler.handleChat(workflowId, request, principal);

        List<ChatStreamResponse> sent = captureSent(1);
        assertThat(sent.get(0).getType()).isEqualTo(StreamType.ERROR);
        assertThat(sent.get(0).getContent()).isEqualTo(ErrorCode.BETA_QUOTA_EXCEEDED.getMessage());
        verify(agentClient, never()).chatStream(any(AgentChatCallParams.class));
    }

    // ─────────────────── withBetaQuotaRefund (exactly-once 환불) ────────────

    @Test
    @DisplayName("DONE 수신 후 정상 종료 — 환불하지 않는다")
    void withBetaQuotaRefund_doneThenComplete_doesNotRelease() {
        ChatAgentResponse agentResponse = mock(ChatAgentResponse.class);
        Flux<ChatStreamEvent> source = Flux.just(ChatStreamEvent.done(agentResponse));

        StepVerifier.create(handler.withBetaQuotaRefund(source, setup.config(), userId))
            .expectNextCount(1)
            .verifyComplete();

        verify(chatService, never()).releaseBetaQuotaOnFailure(any(), any());
    }

    @Test
    @DisplayName("ERROR 이벤트 수신 후 정상 종료(complete) — DONE을 못 받았으므로 정확히 1회 환불한다")
    void withBetaQuotaRefund_errorEventThenComplete_releasesExactlyOnce() {
        Flux<ChatStreamEvent> source = Flux.just(ChatStreamEvent.error("AI 응답 중 오류가 발생했습니다."));

        StepVerifier.create(handler.withBetaQuotaRefund(source, setup.config(), userId))
            .expectNextCount(1)
            .verifyComplete();

        verify(chatService, org.mockito.Mockito.times(1)).releaseBetaQuotaOnFailure(setup.config(), userId);
    }

    @Test
    @DisplayName("리액티브 error 시그널 — 정확히 1회 환불한다")
    void withBetaQuotaRefund_errorSignal_releasesExactlyOnce() {
        Flux<ChatStreamEvent> source = Flux.error(new RuntimeException("agent 연결 실패"));

        StepVerifier.create(handler.withBetaQuotaRefund(source, setup.config(), userId))
            .verifyError(RuntimeException.class);

        verify(chatService, org.mockito.Mockito.times(1)).releaseBetaQuotaOnFailure(setup.config(), userId);
    }

    @Test
    @DisplayName("cancel(클라 disconnect) — 정확히 1회 환불한다")
    void withBetaQuotaRefund_cancel_releasesExactlyOnce() {
        Flux<ChatStreamEvent> source = Flux.never();

        StepVerifier.create(handler.withBetaQuotaRefund(source, setup.config(), userId))
            .thenCancel()
            .verify();

        verify(chatService, org.mockito.Mockito.times(1)).releaseBetaQuotaOnFailure(setup.config(), userId);
    }
}
