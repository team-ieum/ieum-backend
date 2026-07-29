package com.ieum.api.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.chat.dto.AgentResponseType;
import com.ieum.api.chat.dto.ChatAgentResponse;
import com.ieum.api.chat.dto.ChatRequest;
import com.ieum.api.chat.dto.ChatResponse;
import com.ieum.api.chat.service.AgentClient.AgentChatCallParams;
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
import com.ieum.workflowcore.engine.executor.BetaPlatformProvider;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    @Mock private BetaPlatformProvider betaPlatformProvider;

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
        given(credentialService.getByUserId(userId))
            .willReturn(List.of());

        assertThatThrownBy(() ->
            chatService.resolveAgentConfig(workflowId, userId, null, "ROLE_USER")
        ).isInstanceOf(CustomException.class)
            .hasMessageContaining(ErrorCode.WORKFLOW_HAS_NO_AI_NODE.getMessage());
    }

    @Test
    @DisplayName("크레덴셜이 없어도 ROLE_TESTER는 키 없는 AgentConfig로 채팅할 수 있다 (자체 호스팅 LLM 경로)")
    void resolveAgentConfig_noCredential_selfHostedEligible() {
        WorkflowVersion version = buildVersionWithNodesJson("[]");

        given(workflowCrudService.findLatestVersion(workflowId))
            .willReturn(Optional.of(version));
        given(credentialService.getByUserId(userId))
            .willReturn(List.of());

        ChatService.AgentConfig result = chatService.resolveAgentConfig(workflowId, userId, null, "ROLE_TESTER");

        assertThat(result.llmProvider()).isEqualTo("CLAUDE");
        assertThat(result.decryptedApiKey()).isNull();
    }

    @Test
    @DisplayName("AI 노드의 credentialId가 비어 있어도 ROLE_TESTER는 노드의 llmProvider로 키 없는 AgentConfig를 반환한다")
    void resolveAgentConfig_aiNodeBlankCredential_selfHostedEligible() {
        WorkflowVersion version = buildVersionWithNodesJson(
            "[{\"id\":\"n1\",\"type\":\"AI\",\"label\":\"ai\","
                + "\"config\":{\"llmProvider\":\"OPENAI\",\"credentialId\":\"\"}}]");
        given(workflowCrudService.findLatestVersion(workflowId))
            .willReturn(Optional.of(version));

        ChatService.AgentConfig result = chatService.resolveAgentConfig(workflowId, userId, null, "ROLE_TESTER");

        assertThat(result.llmProvider()).isEqualTo("OPENAI");
        assertThat(result.decryptedApiKey()).isNull();
    }

    @Test
    @DisplayName("AI 노드의 credentialId가 비어 있고 자격 없는 ROLE_USER면 WORKFLOW_HAS_NO_AI_NODE 예외")
    void resolveAgentConfig_aiNodeBlankCredential_notEligible_throwsException() {
        WorkflowVersion version = buildVersionWithNodesJson(
            "[{\"id\":\"n1\",\"type\":\"AI\",\"label\":\"ai\","
                + "\"config\":{\"llmProvider\":\"OPENAI\",\"credentialId\":\"\"}}]");
        given(workflowCrudService.findLatestVersion(workflowId))
            .willReturn(Optional.of(version));

        assertThatThrownBy(() ->
            chatService.resolveAgentConfig(workflowId, userId, null, "ROLE_USER")
        ).isInstanceOf(CustomException.class)
            .hasMessageContaining(ErrorCode.WORKFLOW_HAS_NO_AI_NODE.getMessage());
    }

    @Test
    @DisplayName("AI 노드의 credentialId가 비어 있고 self-hosted 자격은 없어도 베타 자격이면 platform 키 자격만 표시한다 (INCR 없음)")
    void resolveAgentConfig_aiNodeBlankCredential_betaEligible() {
        WorkflowVersion version = buildVersionWithNodesJson(
            "[{\"id\":\"n1\",\"type\":\"AI\",\"label\":\"ai\","
                + "\"config\":{\"llmProvider\":\"OPENAI\",\"credentialId\":\"\"}}]");
        given(workflowCrudService.findLatestVersion(workflowId))
            .willReturn(Optional.of(version));
        given(betaPlatformProvider.isBetaEligible(userId)).willReturn(true);

        ChatService.AgentConfig result = chatService.resolveAgentConfig(workflowId, userId, null, "ROLE_USER");

        assertThat(result.llmProvider()).isEqualTo("OPENAI");
        assertThat(result.decryptedApiKey()).isNull();
        assertThat(result.useBetaPlatformKey()).isTrue();
        // 쿼터 예약(INCR)은 agent 호출 직전(chat()/reserveBetaQuota)에서만 한다 — resolveAgentConfig는 자격만 판정
        verify(betaPlatformProvider, never()).reserveQuota(any());
    }

    @Test
    @DisplayName("AI 노드가 없고 크레덴셜도 없지만 베타 자격이면 platform 키 자격만 표시한다 (INCR 없음)")
    void resolveAgentConfig_noCredential_betaEligible() {
        WorkflowVersion version = buildVersionWithNodesJson("[]");
        given(workflowCrudService.findLatestVersion(workflowId))
            .willReturn(Optional.of(version));
        given(credentialService.getByUserId(userId))
            .willReturn(List.of());
        given(betaPlatformProvider.isBetaEligible(userId)).willReturn(true);

        ChatService.AgentConfig result = chatService.resolveAgentConfig(workflowId, userId, null, "ROLE_USER");

        assertThat(result.llmProvider()).isEqualTo("CLAUDE");
        assertThat(result.decryptedApiKey()).isNull();
        assertThat(result.useBetaPlatformKey()).isTrue();
        verify(betaPlatformProvider, never()).reserveQuota(any());
    }

    @Test
    @DisplayName("credentialId(BYOK)가 있으면 베타 자격이어도 platform 미적용, betaPlatformProvider 미호출")
    void resolveAgentConfig_credentialPresent_ignoresBetaEligibility() {
        WorkflowVersion version = buildVersionWithNodesJson(
            "[{\"id\":\"n1\",\"type\":\"AI\",\"label\":\"ai\","
                + "\"config\":{\"llmProvider\":\"OPENAI\",\"credentialId\":\"cred-1\"}}]");
        given(workflowCrudService.findLatestVersion(workflowId))
            .willReturn(Optional.of(version));
        given(credentialProvider.getDecryptedApiKey("cred-1")).willReturn("decrypted-key");

        ChatService.AgentConfig result = chatService.resolveAgentConfig(workflowId, userId, null, "ROLE_USER");

        assertThat(result.decryptedApiKey()).isEqualTo("decrypted-key");
        assertThat(result.useBetaPlatformKey()).isFalse();
        verify(betaPlatformProvider, never()).isBetaEligible(any());
        verify(betaPlatformProvider, never()).reserveQuota(any());
    }

    @Test
    @DisplayName("AI 노드가 없고 fallbackCredentialId가 null일 때, 등록된 크레덴셜이 있으면 자동으로 사용하여 AgentConfig를 반환한다")
    void resolveAgentConfig_noAiNode_autoFallbackToUserCredential() {
        WorkflowVersion version = buildVersionWithNodesJson("[]");
        UUID autoCredentialId = UUID.randomUUID();
        Credential credential = Mockito.mock(Credential.class);

        given(workflowCrudService.findLatestVersion(workflowId))
            .willReturn(Optional.of(version));
        given(credential.getId()).willReturn(autoCredentialId);
        given(credential.getProvider()).willReturn(AiProvider.CLAUDE);
        given(credentialService.getByUserId(userId))
            .willReturn(List.of(credential));
        given(credentialService.getByIdAndUserId(autoCredentialId, userId))
            .willReturn(credential);
        given(credentialProvider.getDecryptedApiKey(autoCredentialId.toString()))
            .willReturn("auto-decrypted-key");

        ChatService.AgentConfig result = chatService.resolveAgentConfig(workflowId, userId, null, "ROLE_USER");

        assertThat(result.llmProvider()).isEqualTo("CLAUDE");
        assertThat(result.decryptedApiKey()).isEqualTo("auto-decrypted-key");
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
        given(agentClient.chat(any(AgentChatCallParams.class)))
            .willReturn(buildAgentResponse("WORKFLOW_GENERATED", "AI 테스트 워크플로우"));
        given(workflowCrudService.findMaxVersionByWorkflowId(workflowId)).willReturn(1);
        given(messageRepository.save(any())).willReturn(agentMsg);

        chatService.chat(workflowId, userId, "ROLE_USER", buildRequest("워크플로우 만들어줘", fallbackCredentialId));

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
        given(agentClient.chat(any(AgentChatCallParams.class)))
            .willReturn(buildAgentResponse("WORKFLOW_GENERATED", "수정된 이름"));
        given(workflowCrudService.findMaxVersionByWorkflowId(workflowId)).willReturn(2);
        given(messageRepository.save(any())).willReturn(agentMsg);

        chatService.chat(workflowId, userId, "ROLE_USER", buildRequest("노드 하나 추가해줘", fallbackCredentialId));

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
        given(agentClient.chat(any(AgentChatCallParams.class)))
            .willReturn(buildAgentResponse("WORKFLOW_GENERATED", null));
        given(workflowCrudService.findMaxVersionByWorkflowId(workflowId)).willReturn(1);
        given(messageRepository.save(any())).willReturn(agentMsg);

        chatService.chat(workflowId, userId, "ROLE_USER", buildRequest("워크플로우 만들어줘", fallbackCredentialId));

        verify(workflowCrudService, never()).updateWorkflowName(any(), any());
    }

    // ─────────────────── chat() 베타 platform 키 ───────────────────────────

    @Test
    @DisplayName("베타 platform 키 사용 시 agentClient.chat에 useBetaPlatformKey=true로 전달되고 쿼터를 예약한다")
    void chat_betaPlatformKey_passesFlagToAgentClient() throws Exception {
        ChatSession session = buildSession(workflowId, userId);
        ChatMessage agentMsg = buildMessage(session, MessageType.AGENT, "응답");
        WorkflowVersion version = buildVersionWithNodesJson("[]");

        given(sessionRepository.save(any())).willReturn(session);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(betaPlatformProvider.isBetaEligible(userId)).willReturn(true);
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        given(agentClient.chat(any(AgentChatCallParams.class)))
            .willReturn(buildAgentResponse("CLARIFICATION_NEEDED", null));
        given(messageRepository.save(any())).willReturn(agentMsg);

        chatService.chat(workflowId, userId, "ROLE_USER", buildRequest("안녕", null));

        verify(betaPlatformProvider).reserveQuota(userId);
        verify(agentClient).chat(argThat(params ->
            userId.equals(params.userId()) && "ROLE_USER".equals(params.userRole()) && params.useBetaPlatformKey()));
    }

    @Test
    @DisplayName("베타 platform 키 사용 + 응답에 토큰 정보가 있으면 recordTokens를 호출한다 (agent usage 대비 관용 배선)")
    void chat_betaPlatformKey_recordsTokensWhenUsagePresent() {
        ChatSession session = buildSession(workflowId, userId);
        ChatMessage agentMsg = buildMessage(session, MessageType.AGENT, "응답");
        WorkflowVersion version = buildVersionWithNodesJson("[]");
        ChatAgentResponse resp = Mockito.mock(ChatAgentResponse.class);
        given(resp.isWorkflowResult()).willReturn(false);
        given(resp.getType()).willReturn(AgentResponseType.CLARIFICATION_NEEDED);
        given(resp.getContent()).willReturn("응답");
        given(resp.getInputTokens()).willReturn(100);
        given(resp.getOutputTokens()).willReturn(50);

        given(sessionRepository.save(any())).willReturn(session);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(betaPlatformProvider.isBetaEligible(userId)).willReturn(true);
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        given(agentClient.chat(any(AgentChatCallParams.class)))
            .willReturn(resp);
        given(messageRepository.save(any())).willReturn(agentMsg);

        chatService.chat(workflowId, userId, "ROLE_USER", buildRequest("안녕", null));

        verify(betaPlatformProvider).recordTokens(userId, 150L);
    }

    @Test
    @DisplayName("베타 platform 키 사용 + 응답 usage가 null(모델이 토큰 미보고)이면 recordTokens를 호출하지 않는다")
    void chat_betaPlatformKey_skipsRecordTokensWhenUsageNull() throws Exception {
        ChatSession session = buildSession(workflowId, userId);
        ChatMessage agentMsg = buildMessage(session, MessageType.AGENT, "응답");
        WorkflowVersion version = buildVersionWithNodesJson("[]");

        given(sessionRepository.save(any())).willReturn(session);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(betaPlatformProvider.isBetaEligible(userId)).willReturn(true);
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        // buildAgentResponse는 실제 ChatAgentResponse — getInputTokens/getOutputTokens는 하드코딩 null
        given(agentClient.chat(any(AgentChatCallParams.class)))
            .willReturn(buildAgentResponse("CLARIFICATION_NEEDED", null));
        given(messageRepository.save(any())).willReturn(agentMsg);

        chatService.chat(workflowId, userId, "ROLE_USER", buildRequest("안녕", null));

        verify(betaPlatformProvider, never()).recordTokens(any(), anyLong());
    }

    @Test
    @DisplayName("chat() — 베타 쿼터 초과 시 BETA_QUOTA_EXCEEDED가 그대로 전파되고 agentClient는 호출되지 않는다")
    void chat_betaQuotaExceeded_propagatesException() {
        WorkflowVersion version = buildVersionWithNodesJson("[]");
        given(sessionRepository.save(any())).willReturn(buildSession(workflowId, userId));
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(betaPlatformProvider.isBetaEligible(userId)).willReturn(true);
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        // reserveQuota는 이제 사전작업(메시지 저장/연동 조회 등) 이후 agent 호출 직전에만 호출되므로
        // 그 흐름을 그대로 타도록 buildRequest()로 필요한 스텁을 모두 채운다.
        Mockito.doThrow(new CustomException(ErrorCode.BETA_QUOTA_EXCEEDED))
            .when(betaPlatformProvider).reserveQuota(userId);

        assertThatThrownBy(() ->
            chatService.chat(workflowId, userId, "ROLE_USER", buildRequestBeforeReserve("안녕", null))
        ).isInstanceOf(CustomException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BETA_QUOTA_EXCEEDED);

        verify(agentClient, never()).chat(any(AgentChatCallParams.class));
        // reserveQuota 자체가 실패(쿼터 초과)했으니 INCR이 반영된 요청이 아니다 — 환불 대상 아님
        verify(betaPlatformProvider, never()).releaseDailyCall(any());
    }

    @Test
    @DisplayName("chat() — 베타 자격이어도 reserve 이전 사전작업에서 예외가 나면 쿼터를 건드리지 않는다 (INCR 자체가 없음)")
    void chat_betaEligible_preReserveStepThrows_neverTouchesQuota() {
        WorkflowVersion version = buildVersionWithNodesJson("[]");
        given(sessionRepository.save(any())).willReturn(buildSession(workflowId, userId));
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(betaPlatformProvider.isBetaEligible(userId)).willReturn(true);
        // reserve 이전 사전작업(연동 상태 조회) 단계에서 예외 발생
        given(integrationContextService.resolve(userId))
            .willThrow(new RuntimeException("연동 상태 조회 실패"));

        assertThatThrownBy(() ->
            chatService.chat(workflowId, userId, "ROLE_USER", buildRequestBeforeReserve("안녕", null))
        ).isInstanceOf(RuntimeException.class);

        verify(betaPlatformProvider, never()).reserveQuota(any());
        verify(betaPlatformProvider, never()).releaseDailyCall(any());
        verify(agentClient, never()).chat(any(AgentChatCallParams.class));
    }

    @Test
    @DisplayName("chat() — 베타 platform 키 예약 후 agentClient 호출이 실패하면 일일 카운터를 환불한다")
    void chat_betaPlatformKey_agentClientThrows_releasesDailyCall() {
        WorkflowVersion version = buildVersionWithNodesJson("[]");
        given(sessionRepository.save(any())).willReturn(buildSession(workflowId, userId));
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(betaPlatformProvider.isBetaEligible(userId)).willReturn(true);
        given(betaPlatformProvider.reserveQuota(userId)).willReturn("beta:calls:" + userId + ":test-key");
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        given(agentClient.chat(any(AgentChatCallParams.class)))
            .willThrow(new CustomException(ErrorCode.PROVIDER_ERROR));

        assertThatThrownBy(() ->
            chatService.chat(workflowId, userId, "ROLE_USER", buildRequest("안녕", null))
        ).isInstanceOf(CustomException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROVIDER_ERROR);

        verify(betaPlatformProvider).reserveQuota(userId);
        // reserve가 반환한 바로 그 키로 환불한다(자정 경계에도 동일 날짜 키를 보장하는 A-2 강건화)
        verify(betaPlatformProvider).releaseDailyCall("beta:calls:" + userId + ":test-key");
    }

    @Test
    @DisplayName("chat() — 베타 platform 키 예약 후 agentClient 호출이 성공하면 일일 카운터를 환불하지 않는다")
    void chat_betaPlatformKey_agentClientSucceeds_doesNotReleaseDailyCall() throws Exception {
        ChatSession session = buildSession(workflowId, userId);
        ChatMessage agentMsg = buildMessage(session, MessageType.AGENT, "응답");
        WorkflowVersion version = buildVersionWithNodesJson("[]");

        given(sessionRepository.save(any())).willReturn(session);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(betaPlatformProvider.isBetaEligible(userId)).willReturn(true);
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        given(agentClient.chat(any(AgentChatCallParams.class)))
            .willReturn(buildAgentResponse("CLARIFICATION_NEEDED", null));
        given(messageRepository.save(any())).willReturn(agentMsg);

        chatService.chat(workflowId, userId, "ROLE_USER", buildRequest("안녕", null));

        verify(betaPlatformProvider, never()).releaseDailyCall(any());
    }

    @Test
    @DisplayName("releaseBetaQuotaOnFailure(key) — key가 null이면 아무 것도 하지 않는다")
    void releaseBetaQuotaOnFailure_nullKey_noOp() {
        chatService.releaseBetaQuotaOnFailure(null);

        verify(betaPlatformProvider, never()).releaseDailyCall(any());
    }

    @Test
    @DisplayName("releaseBetaQuotaOnFailure(key) — key가 있으면 그 키로 환불한다")
    void releaseBetaQuotaOnFailure_withKey_releasesThatKey() {
        chatService.releaseBetaQuotaOnFailure("beta:calls:test-key");

        verify(betaPlatformProvider).releaseDailyCall("beta:calls:test-key");
    }

    @Test
    @DisplayName("reserveBetaQuota — useBetaPlatformKey=false면 위임 없이 null을 반환한다")
    void reserveBetaQuota_nonBetaConfig_returnsNullWithoutDelegating() {
        ChatService.AgentConfig nonBeta = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        String key = chatService.reserveBetaQuota(nonBeta, userId);

        assertThat(key).isNull();
        verify(betaPlatformProvider, never()).reserveQuota(any());
    }

    @Test
    @DisplayName("reserveBetaQuota — useBetaPlatformKey=true면 BetaPlatformProvider가 반환한 키를 그대로 반환한다")
    void reserveBetaQuota_betaConfig_returnsProviderKey() {
        ChatService.AgentConfig beta = new ChatService.AgentConfig("GEMINI", null, null, true);
        given(betaPlatformProvider.reserveQuota(userId)).willReturn("beta:calls:test-key");

        String key = chatService.reserveBetaQuota(beta, userId);

        assertThat(key).isEqualTo("beta:calls:test-key");
    }

    // ─────────────────── finalizeStream (스트리밍 done 후처리) ──────────────

    @Test
    @DisplayName("finalizeStream — WORKFLOW_GENERATED는 워크플로우 버전 저장 + 이름 설정 + 메시지 저장")
    void finalizeStream_workflowGenerated() {
        ChatAgentResponse resp = Mockito.mock(ChatAgentResponse.class);
        given(resp.isWorkflowResult()).willReturn(true);
        given(resp.getType()).willReturn(AgentResponseType.WORKFLOW_GENERATED);
        given(resp.getNodes()).willReturn(List.of());
        given(resp.getEdges()).willReturn(List.of());
        given(resp.getWorkflowName()).willReturn("테스트 WF");
        given(resp.getContent()).willReturn("완성됐어요");

        given(workflowCrudService.findMaxVersionByWorkflowId(workflowId)).willReturn(1);
        ChatSession session = buildSession(workflowId, userId);
        given(sessionRepository.findById(sessionId)).willReturn(Optional.of(session));
        ChatMessage saved = buildMessage(session, MessageType.AGENT, "완성됐어요");
        given(messageRepository.save(any(ChatMessage.class))).willReturn(saved);

        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        ChatResponse result = chatService.finalizeStream(
            workflowId, sessionId, resp, config, UUID.randomUUID(), userId);

        verify(workflowCrudService).saveAgentVersion(eq(workflowId), any(), any());
        verify(workflowCrudService).updateWorkflowName(workflowId, "테스트 WF");
        verify(messageRepository).save(any(ChatMessage.class));
        assertThat(result.getType()).isEqualTo(AgentResponseType.WORKFLOW_GENERATED);
        assertThat(result.getContent()).isEqualTo("완성됐어요");
    }

    @Test
    @DisplayName("finalizeStream — 트랜잭션이 열려 있으면 토큰 기록을 커밋 이후로 미룬다")
    void finalizeStream_defersTokenRecordUntilAfterCommit() {
        ChatAgentResponse resp = Mockito.mock(ChatAgentResponse.class);
        given(resp.isWorkflowResult()).willReturn(false);
        given(resp.getType()).willReturn(AgentResponseType.CLARIFICATION_NEEDED);
        given(resp.getContent()).willReturn("응답");
        given(resp.getInputTokens()).willReturn(100);
        given(resp.getOutputTokens()).willReturn(50);

        ChatSession session = buildSession(workflowId, userId);
        given(sessionRepository.findById(sessionId)).willReturn(Optional.of(session));
        given(messageRepository.save(any(ChatMessage.class)))
            .willReturn(buildMessage(session, MessageType.AGENT, "응답"));

        ChatService.AgentConfig config = new ChatService.AgentConfig("GEMINI", null, null, true);

        TransactionSynchronizationManager.initSynchronization();
        try {
            chatService.finalizeStream(workflowId, sessionId, resp, config, UUID.randomUUID(), userId);

            // 트랜잭션이 아직 안 끝났으므로 Redis에 기록되면 안 된다
            // (JPA 저장이 롤백되면 사용자가 쓰지도 않은 토큰을 잃는다)
            verify(betaPlatformProvider, never()).recordTokens(any(), anyLong());

            TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

            verify(betaPlatformProvider).recordTokens(userId, 150L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("차감 기준은 입출력 합산이 아니라 agent가 보낸 totalTokens다")
    void recordTokens_usesAgentTotalTokens() {
        ChatSession session = buildSession(workflowId, userId);
        ChatMessage agentMsg = buildMessage(session, MessageType.AGENT, "응답");
        WorkflowVersion version = buildVersionWithNodesJson("[]");
        ChatAgentResponse resp = Mockito.mock(ChatAgentResponse.class);
        given(resp.isWorkflowResult()).willReturn(false);
        given(resp.getType()).willReturn(AgentResponseType.CLARIFICATION_NEEDED);
        given(resp.getContent()).willReturn("응답");
        // 캐시드·reasoning 토큰이 있으면 total != prompt + completion이다
        given(resp.getTotalTokens()).willReturn(900);

        given(sessionRepository.save(any())).willReturn(session);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(betaPlatformProvider.isBetaEligible(userId)).willReturn(true);
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        given(agentClient.chat(any(AgentChatCallParams.class))).willReturn(resp);
        given(messageRepository.save(any())).willReturn(agentMsg);

        chatService.chat(workflowId, userId, "ROLE_USER", buildRequest("안녕", null));

        verify(betaPlatformProvider).recordTokens(userId, 900L);
    }

    @Test
    @DisplayName("토큰 합계가 0이면 recordTokens를 호출하지 않는다 (무의미한 Redis 왕복 방지)")
    void recordTokens_skipsWhenZero() {
        ChatSession session = buildSession(workflowId, userId);
        ChatMessage agentMsg = buildMessage(session, MessageType.AGENT, "응답");
        WorkflowVersion version = buildVersionWithNodesJson("[]");
        ChatAgentResponse resp = Mockito.mock(ChatAgentResponse.class);
        given(resp.isWorkflowResult()).willReturn(false);
        given(resp.getType()).willReturn(AgentResponseType.CLARIFICATION_NEEDED);
        given(resp.getContent()).willReturn("응답");
        given(resp.getTotalTokens()).willReturn(0);

        given(sessionRepository.save(any())).willReturn(session);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(betaPlatformProvider.isBetaEligible(userId)).willReturn(true);
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        given(agentClient.chat(any(AgentChatCallParams.class))).willReturn(resp);
        given(messageRepository.save(any())).willReturn(agentMsg);

        chatService.chat(workflowId, userId, "ROLE_USER", buildRequest("안녕", null));

        verify(betaPlatformProvider, never()).recordTokens(any(), anyLong());
    }

    @Test
    @DisplayName("finalizeStream — CLARIFICATION_NEEDED는 워크플로우를 저장하지 않고 메시지만 저장")
    void finalizeStream_clarificationNeeded() {
        ChatAgentResponse resp = Mockito.mock(ChatAgentResponse.class);
        given(resp.isWorkflowResult()).willReturn(false);
        given(resp.getType()).willReturn(AgentResponseType.CLARIFICATION_NEEDED);
        given(resp.getContent()).willReturn("좀 더 알려주세요");

        ChatSession session = buildSession(workflowId, userId);
        given(sessionRepository.findById(sessionId)).willReturn(Optional.of(session));
        ChatMessage saved = buildMessage(session, MessageType.AGENT, "좀 더 알려주세요");
        given(messageRepository.save(any(ChatMessage.class))).willReturn(saved);

        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        ChatResponse result = chatService.finalizeStream(
            workflowId, sessionId, resp, config, UUID.randomUUID(), userId);

        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any());
        verify(messageRepository).save(any(ChatMessage.class));
        assertThat(result.getContent()).isEqualTo("좀 더 알려주세요");
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

    /**
     * reserveQuota 이전에 예외로 끝나는 시나리오용 — getCurrentNodes()/getCurrentEdges()는
     * AgentChatCallParams 빌드 시점(reserveQuota 이후)에만 쓰이므로 미리 스텁하면 strict
     * stubbing에서 미사용으로 실패한다. 필요한 최소 스텁만 둔다.
     */
    private ChatRequest buildRequestBeforeReserve(String prompt, UUID credentialId) {
        ChatRequest request = Mockito.mock(ChatRequest.class);
        given(request.getPrompt()).willReturn(prompt);
        given(request.getSessionId()).willReturn(null);
        given(request.getCredentialId()).willReturn(credentialId);
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
