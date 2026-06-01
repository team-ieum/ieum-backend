package com.ieum.api.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.chat.dto.ChatAgentResponse;
import com.ieum.api.chat.dto.ChatRequest;
import com.ieum.api.chat.service.IntegrationContextService.IntegrationContext;
import com.ieum.api.credential.domain.AiProvider;
import com.ieum.api.credential.domain.Credential;
import com.ieum.api.credential.service.CredentialService;
import com.ieum.api.mcp.repository.McpServerCatalogRepository;
import com.ieum.api.webhookcredential.repository.WebhookCredentialRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.chat.domain.ChatMessage;
import com.ieum.workflowcore.chat.domain.ChatSession;
import com.ieum.workflowcore.chat.domain.MessageType;
import com.ieum.workflowcore.chat.repository.ChatMessageRepository;
import com.ieum.workflowcore.chat.repository.ChatSessionRepository;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.engine.executor.CredentialProvider;
import com.ieum.workflowcore.engine.executor.GitHubTokenProvider;
import com.ieum.workflowcore.engine.executor.GoogleTokenProvider;
import com.ieum.workflowcore.engine.executor.NotionTokenProvider;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatService 단위 테스트")
class ChatServiceTest {

    @Mock private ChatSessionRepository sessionRepository;
    @Mock private ChatMessageRepository messageRepository;
    @Mock private WorkflowCrudService workflowCrudService;
    @Mock private CredentialProvider credentialProvider;
    @Mock private CredentialService credentialService;
    @Mock private GoogleTokenProvider googleTokenProvider;
    @Mock private GitHubTokenProvider gitHubTokenProvider;
    @Mock private NotionTokenProvider notionTokenProvider;
    @Mock private IntegrationContextService integrationContextService;
    @Mock private AgentClient agentClient;
    @Mock private McpServerCatalogRepository mcpServerCatalogRepository;
    @Mock private WebhookCredentialRepository webhookCredentialRepository;

    @InjectMocks
    private ChatService chatService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UUID workflowId;
    private UUID userId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        // ObjectMapper는 @InjectMocks가 주입 못하는 경우 직접 설정
        ReflectionTestUtils.setField(chatService, "objectMapper", objectMapper);
        workflowId = UUID.randomUUID();
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
    }

    // ─────────────────── createOrGetSession ────────────────────────────────

    @Test
    @DisplayName("sessionId가 null이면 새 세션을 생성한다")
    void createOrGetSession_noSessionId_createsNew() {
        ChatSession newSession = buildSession(workflowId, userId);
        given(sessionRepository.save(any(ChatSession.class))).willReturn(newSession);

        ChatSession result = chatService.createOrGetSession(workflowId, userId, null);

        assertThat(result).isEqualTo(newSession);
        verify(sessionRepository).save(any(ChatSession.class));
    }

    @Test
    @DisplayName("sessionId가 있으면 기존 세션을 반환한다")
    void createOrGetSession_withSessionId_returnsExisting() {
        ChatSession existing = buildSession(workflowId, userId);
        given(sessionRepository.findByIdAndUserId(sessionId, userId))
            .willReturn(Optional.of(existing));

        ChatSession result = chatService.createOrGetSession(workflowId, userId, sessionId);

        assertThat(result).isEqualTo(existing);
    }

    @Test
    @DisplayName("존재하지 않는 sessionId 조회 시 CHAT_SESSION_NOT_FOUND 예외")
    void createOrGetSession_notFound_throwsException() {
        given(sessionRepository.findByIdAndUserId(sessionId, userId))
            .willReturn(Optional.empty());

        assertThatThrownBy(() ->
            chatService.createOrGetSession(workflowId, userId, sessionId)
        ).isInstanceOf(CustomException.class)
            .hasMessageContaining(ErrorCode.CHAT_SESSION_NOT_FOUND.getMessage());
    }

    // ─────────────────── getChatHistory ────────────────────────────────────

    @Test
    @DisplayName("채팅 히스토리 조회 — 소유자 검증 후 페이지네이션 반환")
    void getChatHistory_success() {
        ChatSession session = buildSession(workflowId, userId);
        ChatMessage msg = buildMessage(session, MessageType.USER, "안녕");
        Page<ChatMessage> page = new PageImpl<>(List.of(msg));

        given(sessionRepository.findByIdAndUserId(sessionId, userId))
            .willReturn(Optional.of(session));
        given(messageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(0, 20)))
            .willReturn(page);

        Page<ChatMessage> result = chatService.getChatHistory(sessionId, userId, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getContent()).isEqualTo("안녕");
    }

    @Test
    @DisplayName("다른 사용자의 세션 히스토리 조회 시 CHAT_SESSION_NOT_FOUND 예외")
    void getChatHistory_unauthorized_throwsException() {
        given(sessionRepository.findByIdAndUserId(sessionId, userId))
            .willReturn(Optional.empty());

        assertThatThrownBy(() ->
            chatService.getChatHistory(sessionId, userId, PageRequest.of(0, 20))
        ).isInstanceOf(CustomException.class)
            .hasMessageContaining(ErrorCode.CHAT_SESSION_NOT_FOUND.getMessage());
    }

    // ─────────────────── resolveAgentConfig ────────────────────────────────

    @Test
    @DisplayName("AI 노드가 없는 워크플로우는 WORKFLOW_HAS_NO_AI_NODE 예외")
    void resolveAgentConfig_noAiNode_throwsException() {
        WorkflowVersion version = buildVersionWithNodesJson("[]");
        given(workflowCrudService.findLatestVersion(workflowId))
            .willReturn(Optional.of(version));

        assertThatThrownBy(() ->
            chatService.resolveAgentConfig(workflowId, userId, null)
        ).isInstanceOf(CustomException.class)
            .hasMessageContaining(ErrorCode.WORKFLOW_HAS_NO_AI_NODE.getMessage());
    }

    // ─────────────────── saveUserMessage ───────────────────────────────────

    @Test
    @DisplayName("saveUserMessage — USER 타입으로 저장")
    void saveUserMessage_savesWithUserType() {
        ChatSession session = buildSession(workflowId, userId);
        ChatMessage saved = buildMessage(session, MessageType.USER, "테스트");
        given(messageRepository.save(any(ChatMessage.class))).willReturn(saved);

        ChatMessage result = chatService.saveUserMessage(session, "테스트");

        assertThat(result.getSenderType()).isEqualTo(MessageType.USER);
        assertThat(result.getContent()).isEqualTo("테스트");
    }

    // ─────────────────── saveAgentMessage ──────────────────────────────────

    @Test
    @DisplayName("saveAgentMessage — AGENT 타입으로 저장")
    void saveAgentMessage_savesWithAgentType() {
        ChatSession session = buildSession(workflowId, userId);
        ChatMessage saved = buildMessage(session, MessageType.AGENT, "AI 응답");
        given(messageRepository.save(any(ChatMessage.class))).willReturn(saved);

        ChatMessage result = chatService.saveAgentMessage(session, "AI 응답", 10, 20);

        assertThat(result.getSenderType()).isEqualTo(MessageType.AGENT);
    }

    // ─────────────────── chat() auto-naming ────────────────────────────────

    @Test
    @DisplayName("첫 생성(maxVersion == 1)이고 workflowName이 있으면 워크플로우 이름을 업데이트한다")
    void chat_firstGeneration_autoNamesWorkflow() throws Exception {
        UUID fallbackCredentialId = UUID.randomUUID();

        ChatSession session = buildSession(workflowId, userId);
        ChatMessage agentMsg = buildMessage(session, MessageType.AGENT, "워크플로우를 생성했습니다");
        WorkflowVersion version = Mockito.mock(WorkflowVersion.class);
        Credential credential = Mockito.mock(Credential.class);

        given(credential.getProvider()).willReturn(AiProvider.CLAUDE);
        given(sessionRepository.save(any())).willReturn(session);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(workflowCrudService.loadDefinition(version)).willReturn(buildDoc(List.of()));
        given(credentialService.getByIdAndUserId(fallbackCredentialId, userId)).willReturn(credential);
        given(credentialProvider.getDecryptedApiKey(fallbackCredentialId.toString())).willReturn("test-api-key");
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        given(agentClient.chat(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .willReturn(buildAgentResponse("WORKFLOW_GENERATED", "AI 테스트 워크플로우"));
        given(workflowCrudService.findMaxVersionByWorkflowId(workflowId)).willReturn(1);
        given(messageRepository.save(any())).willReturn(agentMsg);

        chatService.chat(workflowId, userId, buildRequest("워크플로우 만들어줘", fallbackCredentialId));

        verify(workflowCrudService).updateWorkflowName(workflowId, "AI 테스트 워크플로우");
    }

    @Test
    @DisplayName("후속 메시지(maxVersion > 1)이면 workflowName이 있어도 이름을 덮어쓰지 않는다")
    void chat_subsequentMessage_doesNotOverrideName() throws Exception {
        UUID fallbackCredentialId = UUID.randomUUID();

        ChatSession session = buildSession(workflowId, userId);
        ChatMessage agentMsg = buildMessage(session, MessageType.AGENT, "수정했습니다");
        WorkflowVersion version = Mockito.mock(WorkflowVersion.class);
        Credential credential = Mockito.mock(Credential.class);

        given(credential.getProvider()).willReturn(AiProvider.CLAUDE);
        given(sessionRepository.save(any())).willReturn(session);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(workflowCrudService.loadDefinition(version)).willReturn(buildDoc(List.of()));
        given(credentialService.getByIdAndUserId(fallbackCredentialId, userId)).willReturn(credential);
        given(credentialProvider.getDecryptedApiKey(fallbackCredentialId.toString())).willReturn("test-api-key");
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        given(agentClient.chat(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .willReturn(buildAgentResponse("WORKFLOW_GENERATED", "수정된 이름"));
        given(workflowCrudService.findMaxVersionByWorkflowId(workflowId)).willReturn(2);
        given(messageRepository.save(any())).willReturn(agentMsg);

        chatService.chat(workflowId, userId, buildRequest("노드 하나 추가해줘", fallbackCredentialId));

        verify(workflowCrudService, never()).updateWorkflowName(any(), any());
    }

    @Test
    @DisplayName("agent가 workflowName을 null로 반환하면 이름 업데이트를 호출하지 않는다")
    void chat_workflowNameIsNull_doesNotUpdateName() throws Exception {
        UUID fallbackCredentialId = UUID.randomUUID();

        ChatSession session = buildSession(workflowId, userId);
        ChatMessage agentMsg = buildMessage(session, MessageType.AGENT, "응답");
        WorkflowVersion version = Mockito.mock(WorkflowVersion.class);
        Credential credential = Mockito.mock(Credential.class);

        given(credential.getProvider()).willReturn(AiProvider.CLAUDE);
        given(sessionRepository.save(any())).willReturn(session);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(workflowCrudService.loadDefinition(version)).willReturn(buildDoc(List.of()));
        given(credentialService.getByIdAndUserId(fallbackCredentialId, userId)).willReturn(credential);
        given(credentialProvider.getDecryptedApiKey(fallbackCredentialId.toString())).willReturn("test-api-key");
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        given(agentClient.chat(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .willReturn(buildAgentResponse("WORKFLOW_GENERATED", null));
        given(workflowCrudService.findMaxVersionByWorkflowId(workflowId)).willReturn(1);
        given(messageRepository.save(any())).willReturn(agentMsg);

        chatService.chat(workflowId, userId, buildRequest("워크플로우 만들어줘", fallbackCredentialId));

        verify(workflowCrudService, never()).updateWorkflowName(any(), any());
    }

    // ─────────────────── 헬퍼 ──────────────────────────────────────────────

    private ChatSession buildSession(UUID workflowId, UUID userId) {
        ChatSession session = ChatSession.builder()
            .workflowId(workflowId)
            .userId(userId)
            .build();
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
        return session;
    }

    private ChatMessage buildMessage(ChatSession session, MessageType type, String content) {
        ChatMessage msg = ChatMessage.builder()
            .session(session)
            .senderType(type)
            .content(content)
            .build();
        ReflectionTestUtils.setField(msg, "id", UUID.randomUUID());
        return msg;
    }

    private ChatRequest buildRequest(String prompt, UUID credentialId) {
        ChatRequest request = Mockito.mock(ChatRequest.class);
        given(request.getPrompt()).willReturn(prompt);
        given(request.getSessionId()).willReturn(null);
        given(request.getCredentialId()).willReturn(credentialId);
        given(request.getCurrentNodes()).willReturn(null);
        given(request.getCurrentEdges()).willReturn(null);
        return request;
    }

    private ChatAgentResponse buildAgentResponse(String type, String workflowName) throws Exception {
        String nameField = workflowName != null
            ? "\"workflowName\": \"" + workflowName + "\","
            : "";
        String json = """
            {
                "message": "응답 메시지",
                "type": "%s",
                %s
                "nodes": [],
                "edges": []
            }
            """.formatted(type, nameField);
        return objectMapper.readValue(json, ChatAgentResponse.class);
    }

    private WorkflowDefinitionDocument buildDoc(List<java.util.Map<String, Object>> nodes) {
        return WorkflowDefinitionDocument.builder()
            .nodes(nodes)
            .edges(List.of())
            .createdAt(LocalDateTime.now())
            .build();
    }

    private WorkflowVersion buildVersionWithNodesJson(String nodesJson) {
        WorkflowVersion version = Mockito.mock(WorkflowVersion.class);
        try {
            WorkflowDefinitionDocument mockDef = WorkflowDefinitionDocument.builder()
                .nodes(objectMapper.readValue(nodesJson, new TypeReference<>() {}))
                .edges(List.of())
                .createdAt(LocalDateTime.now())
                .build();
            given(workflowCrudService.loadDefinition(any(WorkflowVersion.class)))
                .willReturn(mockDef);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Test data setup failed: " + e.getMessage(), e);
        }
        return version;
    }
}
