package com.ieum.api.chat.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ieum.api.chat.dto.ChatAgentResponse;
import com.ieum.api.chat.dto.ChatRequest;
import com.ieum.api.chat.dto.ChatResponse;
import com.ieum.api.chat.dto.ChatStreamEvent;
import com.ieum.api.chat.dto.ChatStreamResponse;
import com.ieum.api.chat.dto.ChatStreamResponse.StreamType;
import com.ieum.api.chat.service.AgentClient;
import com.ieum.api.chat.service.ChatService;
import com.ieum.api.chat.service.ChatService.AgentConfig;
import com.ieum.api.chat.service.ChatService.StreamSetupResult;
import com.ieum.api.chat.service.IntegrationContextService.IntegrationContext;
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
import reactor.core.publisher.Flux;

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
        principal = new UsernamePasswordAuthenticationToken(userId.toString(), null);
        request = mock(ChatRequest.class);

        setup = new StreamSetupResult(
            sessionId,
            new AgentConfig("CLAUDE", "key", null),
            "워크플로우 만들어줘",
            null, null,
            new IntegrationContext(List.of(), List.of()),
            null, null, null,
            List.of(), List.of()
        );
        given(chatService.prepareStream(workflowId, userId, request)).willReturn(setup);
    }

    @SuppressWarnings("unchecked")
    private void mockChatStream(ChatStreamEvent... events) {
        given(agentClient.chatStream(
            any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any()
        )).willReturn(Flux.just(events));
    }

    private List<ChatStreamResponse> captureSent(int times) {
        ArgumentCaptor<ChatStreamResponse> captor = ArgumentCaptor.forClass(ChatStreamResponse.class);
        verify(messagingTemplate, times(times))
            .convertAndSendToUser(eq(userId.toString()), eq(DESTINATION), captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("stage → done 흐름: 진행 프레임 전달 후 done 시 finalizeStream으로 저장하고 최종 프레임 전달")
    void stage_then_done() {
        ChatAgentResponse agentResponse = mock(ChatAgentResponse.class);
        ChatResponse finalResponse = mock(ChatResponse.class);
        given(chatService.finalizeStream(eq(workflowId), eq(sessionId), eq(agentResponse), any(), any()))
            .willReturn(finalResponse);

        mockChatStream(
            ChatStreamEvent.stage("designing"),
            ChatStreamEvent.stage("reviewing"),
            ChatStreamEvent.done(agentResponse)
        );

        handler.handleChat(workflowId, request, principal);

        verify(chatService).finalizeStream(eq(workflowId), eq(sessionId), eq(agentResponse), any(), any());
        List<ChatStreamResponse> sent = captureSent(3);
        assertThat(sent.get(0).getType()).isEqualTo(StreamType.STAGE);
        assertThat(sent.get(0).getStage()).isEqualTo("designing");
        assertThat(sent.get(1).getType()).isEqualTo(StreamType.STAGE);
        assertThat(sent.get(1).getStage()).isEqualTo("reviewing");
        assertThat(sent.get(2).getType()).isEqualTo(StreamType.DONE);
        assertThat(sent.get(2).getData()).isEqualTo(finalResponse);
    }

    @Test
    @DisplayName("error 이벤트: ERROR 프레임을 전달하고 저장은 하지 않는다")
    void error_event() {
        mockChatStream(ChatStreamEvent.error("AI 응답 중 오류가 발생했습니다."));

        handler.handleChat(workflowId, request, principal);

        List<ChatStreamResponse> sent = captureSent(1);
        assertThat(sent.get(0).getType()).isEqualTo(StreamType.ERROR);
        assertThat(sent.get(0).getContent()).isEqualTo("AI 응답 중 오류가 발생했습니다.");
        verify(chatService, org.mockito.Mockito.never())
            .finalizeStream(any(), any(), any(), any(), any());
    }
}
