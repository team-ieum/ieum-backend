package com.ieum.api.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.chat.dto.ChatResponse;
import com.ieum.api.chat.dto.TokenUsage;
import com.ieum.api.chat.service.ChatService;
import com.ieum.api.common.GlobalExceptionHandler;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.chat.domain.ChatMessage;
import com.ieum.workflowcore.chat.domain.ChatSession;
import com.ieum.workflowcore.chat.domain.MessageType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatController 통합 테스트")
class ChatControllerTest {

    @Mock
    private ChatService chatService;

    @InjectMocks
    private ChatController chatController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private UUID workflowId;
    private UUID userId;
    private UUID sessionId;
    private CustomUserDetails userDetails;

    @BeforeEach
    void setUp() {
        workflowId = UUID.randomUUID();
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();

        userDetails = CustomUserDetails.of(userId, "test@example.com", "ROLE_USER");

        // SecurityContext에 인증 정보 설정 (@AuthenticationPrincipal 주입용)
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            userDetails, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(chatController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();
    }

    // ─────────────────── POST /api/v1/workflows/{workflowId}/chat ──────────

    @Test
    @DisplayName("채팅 메시지 전송 성공 — 200 반환")
    void chat_success_returns200() throws Exception {
        ChatResponse response = ChatResponse.builder()
            .messageId(UUID.randomUUID())
            .sessionId(sessionId)
            .senderType(MessageType.AGENT)
            .content("안녕하세요!")
            .tokens(new TokenUsage(10, 20))
            .build();

        given(chatService.chat(eq(workflowId), eq(userId), any())).willReturn(response);

        mockMvc.perform(post("/api/v1/workflows/{workflowId}/chat", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("message", "안녕"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content").value("안녕하세요!"))
            .andExpect(jsonPath("$.data.sessionId").value(sessionId.toString()))
            .andExpect(jsonPath("$.data.senderType").value("AGENT"));
    }

    @Test
    @DisplayName("메시지가 빈 문자열이면 400 반환")
    void chat_blankMessage_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/{workflowId}/chat", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("message", ""))))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("메시지가 2000자 초과이면 400 반환")
    void chat_messageTooLong_returns400() throws Exception {
        String longMessage = "a".repeat(2001);

        mockMvc.perform(post("/api/v1/workflows/{workflowId}/chat", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("message", longMessage))))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("존재하지 않는 세션 ID 전달 시 404 반환")
    void chat_sessionNotFound_returns404() throws Exception {
        given(chatService.chat(eq(workflowId), eq(userId), any()))
            .willThrow(new CustomException(ErrorCode.CHAT_SESSION_NOT_FOUND));

        mockMvc.perform(post("/api/v1/workflows/{workflowId}/chat", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "message", "안녕",
                    "sessionId", sessionId.toString()
                ))))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AI 노드 없는 워크플로우 — 400 반환")
    void chat_noAiNode_returns400() throws Exception {
        given(chatService.chat(eq(workflowId), eq(userId), any()))
            .willThrow(new CustomException(ErrorCode.WORKFLOW_HAS_NO_AI_NODE));

        mockMvc.perform(post("/api/v1/workflows/{workflowId}/chat", workflowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("message", "안녕"))))
            .andExpect(status().isBadRequest());
    }

    // ─────────────────── GET /api/v1/workflows/{workflowId}/chat/history ───

    @Test
    @DisplayName("채팅 히스토리 조회 성공 — 200 반환")
    void getChatHistory_success_returns200() throws Exception {
        ChatSession session = buildSession();
        ChatMessage msg = buildAgentMessage(session, "응답입니다");
        Page<ChatMessage> page = new PageImpl<>(List.of(msg), PageRequest.of(0, 20), 1);

        given(chatService.getChatHistory(eq(sessionId), eq(userId), any())).willReturn(page);

        mockMvc.perform(get("/api/v1/workflows/{workflowId}/chat/history", workflowId)
                .param("sessionId", sessionId.toString())
                .param("page", "0")
                .param("size", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].content").value("응답입니다"))
            .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("다른 사람 세션 히스토리 조회 시 404 반환")
    void getChatHistory_unauthorized_returns404() throws Exception {
        given(chatService.getChatHistory(eq(sessionId), eq(userId), any()))
            .willThrow(new CustomException(ErrorCode.CHAT_SESSION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/workflows/{workflowId}/chat/history", workflowId)
                .param("sessionId", sessionId.toString()))
            .andExpect(status().isNotFound());
    }

    // ─────────────────── 헬퍼 ──────────────────────────────────────────────

    private ChatSession buildSession() {
        ChatSession session = ChatSession.builder()
            .workflowId(workflowId)
            .userId(userId)
            .build();
        ReflectionTestUtils.setField(session, "id", sessionId);
        return session;
    }

    private ChatMessage buildAgentMessage(ChatSession session, String content) {
        ChatMessage msg = ChatMessage.builder()
            .session(session)
            .senderType(MessageType.AGENT)
            .content(content)
            .inputTokens(5)
            .outputTokens(10)
            .build();
        ReflectionTestUtils.setField(msg, "id", UUID.randomUUID());
        return msg;
    }
}
