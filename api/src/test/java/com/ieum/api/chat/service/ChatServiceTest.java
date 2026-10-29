package com.ieum.api.chat.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.catalog.NodeDerivation;
import com.ieum.workflowcore.chat.domain.ChatMessage;
import com.ieum.workflowcore.chat.domain.ChatSession;
import com.ieum.workflowcore.chat.domain.MessageType;
import com.ieum.workflowcore.chat.repository.ChatMessageRepository;
import com.ieum.workflowcore.chat.repository.ChatSessionRepository;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.executor.BetaPlatformProvider;
import com.ieum.workflowcore.engine.executor.CredentialProvider;
import com.ieum.workflowcore.engine.executor.GitHubTokenProvider;
import com.ieum.workflowcore.engine.executor.GoogleTokenProvider;
import com.ieum.workflowcore.engine.executor.NotionTokenProvider;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    @Mock(answer = Answers.CALLS_REAL_METHODS) private BetaPlatformProvider betaPlatformProvider;

    @InjectMocks
    private ChatService chatService;

    private static final EventCatalog CATALOG = EventCatalog.loadFromClasspath();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UUID workflowId;
    private UUID userId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        // ObjectMapper는 @InjectMocks가 주입 못하는 경우 직접 설정
        ReflectionTestUtils.setField(chatService, "objectMapper", objectMapper);
        // NodeDerivation·EventCatalog는 mock이 아니라 실물이다 — @InjectMocks가 null로 채운 자리를 덮는다
        ReflectionTestUtils.setField(chatService, "nodeDerivation", new NodeDerivation(CATALOG));
        ReflectionTestUtils.setField(chatService, "eventCatalog", CATALOG);
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
        given(credentialProvider.getDecryptedApiKey("cred-1", userId)).willReturn("decrypted-key");

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
        given(credentialProvider.getDecryptedApiKey(autoCredentialId.toString(), userId))
            .willReturn("auto-decrypted-key");

        ChatService.AgentConfig result = chatService.resolveAgentConfig(workflowId, userId, null, "ROLE_USER");

        assertThat(result.llmProvider()).isEqualTo("CLAUDE");
        assertThat(result.decryptedApiKey()).isEqualTo("auto-decrypted-key");
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
        given(credentialProvider.getDecryptedApiKey(fallbackCredentialId.toString(), userId)).willReturn("test-api-key");
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
        given(credentialProvider.getDecryptedApiKey(fallbackCredentialId.toString(), userId)).willReturn("test-api-key");
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
        given(credentialProvider.getDecryptedApiKey(fallbackCredentialId.toString(), userId)).willReturn("test-api-key");
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        given(agentClient.chat(any(AgentChatCallParams.class)))
            .willReturn(buildAgentResponse("WORKFLOW_GENERATED", null));
        given(workflowCrudService.findMaxVersionByWorkflowId(workflowId)).willReturn(1);
        given(messageRepository.save(any())).willReturn(agentMsg);

        chatService.chat(workflowId, userId, "ROLE_USER", buildRequest("워크플로우 만들어줘", fallbackCredentialId));

        verify(workflowCrudService, never()).updateWorkflowName(any(), any());
    }

    // ─────────────────── Google 토큰 판정 (ToolAuthRule) ────────────────────

    @Test
    @DisplayName("AI 노드에 builtin:google_ 도구가 있으면 스트림 준비에 Google 토큰을 싣는다")
    void prepareStream_googleBuiltinTool_attachesGoogleToken() {
        ChatSession session = buildSession(workflowId, userId);
        WorkflowVersion version = buildVersionWithNodesJson(
            "[{\"id\":\"n1\",\"type\":\"AI\",\"label\":\"ai\",\"config\":{\"llmProvider\":\"GEMINI\","
                + "\"credentialId\":\"\",\"tools\":[{\"name\":\"builtin:google_gmail_send\"}]}}]");
        given(sessionRepository.save(any())).willReturn(session);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        given(googleTokenProvider.getValidAccessToken(userId)).willReturn("google-token");

        ChatService.StreamSetupResult result =
            chatService.prepareStream(workflowId, userId, "ROLE_TESTER", buildRequest("안녕", null));

        assertThat(result.googleToken()).isEqualTo("google-token");
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

        verify(workflowCrudService).saveAgentVersion(eq(workflowId), any(), any(), any(), any(), eq(false));
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

        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any(), any(), any(), anyBoolean());
        verify(messageRepository).save(any(ChatMessage.class));
        assertThat(result.getContent()).isEqualTo("좀 더 알려주세요");
    }

    // ─────────────────── agent 생성 정의의 웹훅 URL 원문 거부 (IEUM-BE-62) ────

    private static final String SLACK_WEBHOOK =
        "https://hooks.slack.com/services/FAKE-WORKSPACE/FAKE-CHANNEL/not-a-real-token";

    @Test
    @DisplayName("finalizeStream — agent가 만든 노드 config.url이 웹훅 원문이면 저장하지 않는다")
    void finalizeStream_agentNodeWithRawWebhookUrl_notSaved() throws Exception {
        ChatAgentResponse resp = buildWorkflowResponse(
            "{ \"method\": \"POST\", \"url\": \"" + SLACK_WEBHOOK + "\" }");
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        assertThatThrownBy(() -> chatService.finalizeStream(
            workflowId, sessionId, resp, config, UUID.randomUUID(), userId))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_WORKFLOW));

        // 거부는 저장 경로에 닿기 전에 끝나야 한다 — 새 버전이 남으면 막은 의미가 없다.
        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any(), any(), any(), anyBoolean());
        verify(workflowCrudService, never()).updateWorkflowName(any(), any());
        verify(messageRepository, never()).save(any(ChatMessage.class));
    }

    @Test
    @DisplayName("blocking chat — agent가 만든 노드 config.url이 웹훅 원문이면 저장하지 않는다")
    void chat_agentNodeWithRawWebhookUrl_notSaved() throws Exception {
        ChatAgentResponse resp = buildWorkflowResponse(
            "{ \"method\": \"POST\", \"url\": \"" + SLACK_WEBHOOK + "\" }");

        ChatSession session = buildSession(workflowId, userId);
        WorkflowVersion version = buildVersionWithNodesJson("[]");
        given(sessionRepository.save(any())).willReturn(session);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(betaPlatformProvider.isBetaEligible(userId)).willReturn(true);
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        given(agentClient.chat(any(AgentChatCallParams.class))).willReturn(resp);

        assertThatThrownBy(() ->
            chatService.chat(workflowId, userId, "ROLE_USER", buildRequest("웹훅으로 보내줘", null)))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_WORKFLOW));

        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("거부 메시지는 대안을 알려주고 URL을 되싣지 않는다 — REST 경로와 같은 문구")
    void agentRejectionMessage_guidesToCredential_withoutEchoingUrl() throws Exception {
        ChatAgentResponse resp = buildWorkflowResponse(
            "{ \"method\": \"POST\", \"url\": \"" + SLACK_WEBHOOK + "\" }");
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        assertThatThrownBy(() -> chatService.finalizeStream(
            workflowId, sessionId, resp, config, UUID.randomUUID(), userId))
            // 사용자가 다음에 할 일을 알려준다 — 웹훅을 등록하면 agent가 그 id를 쓴다.
            .hasMessageContaining("webhookCredentialId")
            .hasMessageContaining("웹훅 자격증명")
            // 메시지는 WS 핸들러가 사용자에게 그대로 보내고 로그에도 남는다.
            .hasMessageNotContaining(SLACK_WEBHOOK)
            .hasMessageNotContaining("hooks.slack.com");
    }

    @Test
    @DisplayName("웹훅을 webhookCredentialId로 참조하는 정상 정의는 그대로 저장된다 (회귀 방지)")
    void finalizeStream_agentNodeWithWebhookCredentialId_saved() throws Exception {
        ChatAgentResponse resp = buildWorkflowResponse(
            "{ \"method\": \"POST\", \"webhookCredentialId\": \"" + UUID.randomUUID() + "\" }");

        ChatSession session = buildSession(workflowId, userId);
        given(sessionRepository.findById(sessionId)).willReturn(Optional.of(session));
        given(messageRepository.save(any(ChatMessage.class)))
            .willReturn(buildMessage(session, MessageType.AGENT, "완성됐어요"));
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, UUID.randomUUID(), userId);

        verify(workflowCrudService).saveAgentVersion(eq(workflowId), any(), any(), any(), any(), eq(false));
    }

    // ─────────────────── agent 생성 정의의 남의 credentialId 거부 (IEUM-BE-64) ─

    @Test
    @DisplayName("blocking chat — agent가 만든 노드에 남의 credentialId가 있으면 저장하지 않는다")
    void chat_agentNodeWithForeignCredentialId_notSaved() throws Exception {
        UUID foreignCredentialId = UUID.randomUUID();
        ChatAgentResponse resp = buildWorkflowResponse(
            "{ \"credentialId\": \"" + foreignCredentialId + "\" }");

        ChatSession session = buildSession(workflowId, userId);
        WorkflowVersion version = buildVersionWithNodesJson("[]");
        given(sessionRepository.save(any())).willReturn(session);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(betaPlatformProvider.isBetaEligible(userId)).willReturn(true);
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        given(agentClient.chat(any(AgentChatCallParams.class))).willReturn(resp);

        assertThatThrownBy(() ->
            chatService.chat(workflowId, userId, "ROLE_USER", buildRequest("요약해줘", null)))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_WORKFLOW))
            // 메시지는 WS 핸들러가 사용자에게 그대로 보내고 로그에도 남는다.
            .satisfies(e -> assertThat(e.getMessage())
                .doesNotContain(foreignCredentialId.toString()));

        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("소유한 fallback credentialId는 AI 노드에 주입되어 저장된다")
    void finalizeStream_ownedFallbackCredential_injectedAndSaved() throws Exception {
        UUID fallbackCredentialId = UUID.randomUUID();
        ChatAgentResponse resp = buildAiNodeResponse();

        ChatSession session = buildSession(workflowId, userId);
        given(sessionRepository.findById(sessionId)).willReturn(Optional.of(session));
        given(messageRepository.save(any(ChatMessage.class)))
            .willReturn(buildMessage(session, MessageType.AGENT, "완성됐어요"));
        given(credentialService.getByUserId(userId))
            .willReturn(List.of(buildCredential(fallbackCredentialId)));
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, fallbackCredentialId, userId);

        verify(workflowCrudService).saveAgentVersion(
            eq(workflowId),
            argThat(nodesJson -> nodesJson.contains(fallbackCredentialId.toString())),
            any(), any(), any(), eq(false));
    }

    @Test
    @DisplayName("남의 fallback credentialId는 주입 뒤 검사에 걸려 저장되지 않는다")
    void finalizeStream_foreignFallbackCredential_rejected() throws Exception {
        // fallbackCredentialId는 서버가 고른 값이 아니라 요청 본문의 credentialId 원본이고,
        // AI 노드가 있는 워크플로우에서는 resolveAgentConfig가 소유 검증 없이 지나친다.
        // 검사가 주입보다 앞서면 이 값이 그대로 저장된다(IEUM-BE-64 회귀).
        UUID foreignCredentialId = UUID.randomUUID();
        ChatAgentResponse resp = buildAiNodeResponse();

        given(credentialService.getByUserId(userId))
            .willReturn(List.of(buildCredential(UUID.randomUUID())));
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        assertThatThrownBy(() -> chatService.finalizeStream(
            workflowId, sessionId, resp, config, foreignCredentialId, userId))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_WORKFLOW))
            .satisfies(e -> assertThat(e.getMessage())
                .doesNotContain(foreignCredentialId.toString()));

        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("blocking chat — AI 노드가 있는 워크플로우에서도 요청의 남의 credentialId는 주입·저장되지 않는다")
    void chat_foreignRequestCredentialId_notInjectedIntoAiNode() throws Exception {
        // 공격 경로 재현: 자기 워크플로우(AI 노드 보유)에 남의 credentialId를 실어 채팅하면
        // resolveAgentConfig는 노드의 credentialId만 쓰고 요청값은 검증 없이 지나친다.
        UUID ownedCredentialId = UUID.randomUUID();
        UUID foreignCredentialId = UUID.randomUUID();
        ChatAgentResponse resp = buildAiNodeResponse();

        ChatSession session = buildSession(workflowId, userId);
        WorkflowVersion version = buildVersionWithNodesJson(
            "[{\"id\":\"n1\",\"type\":\"AI\",\"config\":{\"llmProvider\":\"CLAUDE\","
                + "\"credentialId\":\"" + ownedCredentialId + "\"}}]");
        given(sessionRepository.save(any())).willReturn(session);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(credentialProvider.getDecryptedApiKey(ownedCredentialId.toString(), userId))
            .willReturn("test-api-key");
        given(credentialService.getByUserId(userId))
            .willReturn(List.of(buildCredential(ownedCredentialId)));
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        given(agentClient.chat(any(AgentChatCallParams.class))).willReturn(resp);

        assertThatThrownBy(() -> chatService.chat(workflowId, userId, "ROLE_USER",
            buildRequest("노드 추가해줘", foreignCredentialId)))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_WORKFLOW));

        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("blocking chat — agent가 만든 노드 config에 API 키 원문이 있으면 저장하지 않는다")
    void chat_agentNodeWithInlineSecret_notSaved() throws Exception {
        // 사용자가 프롬프트에 키를 그대로 적으면 그 값이 노드 config로 돌아온다. config는 Mongo에
        // 평문 저장되고 조회 응답에 그대로 실린다.
        ChatAgentResponse resp = buildWorkflowResponse("{ \"apiKey\": \"sk-live-not-a-real-key\" }");

        ChatSession session = buildSession(workflowId, userId);
        WorkflowVersion version = buildVersionWithNodesJson("[]");
        given(sessionRepository.save(any())).willReturn(session);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(betaPlatformProvider.isBetaEligible(userId)).willReturn(true);
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
        given(agentClient.chat(any(AgentChatCallParams.class))).willReturn(resp);

        assertThatThrownBy(() ->
            chatService.chat(workflowId, userId, "ROLE_USER", buildRequest("요약해줘", null)))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_WORKFLOW))
            // 메시지는 WS 핸들러가 사용자에게 그대로 보내고 로그에도 남는다.
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("sk-live-not-a-real-key"));

        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any(), any(), any(), anyBoolean());
    }

    // ─────────────────── 채팅 저장 경로의 엣지 id 채우기 (IEUM-BE-65) ────────

    @Test
    @DisplayName("agent 엣지에 id가 없으면 저장 직전에 채워진다 — 다음 PUT이 FIFO 폴백으로 떨어지지 않는다")
    void finalizeStream_agentEdgesWithoutId_idsFilled() throws Exception {
        ChatAgentResponse resp = buildResponseWithEdges("""
            [
                { "source": "node-a", "target": "node-b", "conditionType": "true" },
                { "source": "node-a", "target": "node-b", "conditionType": "false" }
            ]
            """);

        givenFinalizeStreamSession();
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, null, userId);

        List<Map<String, Object>> saved = captureSavedEdges();
        assertThat(saved).hasSize(2);
        assertThat(saved).allSatisfy(edge ->
            assertThat((String) edge.get("id")).isNotBlank());
        // 형제 엣지는 서로 다른 id를 받아야 한다 — 같으면 병합의 id 짝짓기가 하나만 집는다.
        assertThat(saved.get(0).get("id")).isNotEqualTo(saved.get(1).get("id"));
        // conditionType은 건드리지 않는다.
        assertThat(saved.get(0).get("conditionType")).isEqualTo("true");
        assertThat(saved.get(1).get("conditionType")).isEqualTo("false");
    }

    @Test
    @DisplayName("agent 엣지에 id가 이미 있으면 그대로 유지한다 — FE currentEdges pass-through 경로")
    void finalizeStream_agentEdgesWithId_idsPreserved() throws Exception {
        ChatAgentResponse resp = buildResponseWithEdges("""
            [ { "id": "edge-keep", "source": "node-a", "target": "node-b" } ]
            """);

        givenFinalizeStreamSession();
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, null, userId);

        assertThat(captureSavedEdges()).singleElement()
            .satisfies(edge -> assertThat(edge.get("id")).isEqualTo("edge-keep"));
    }

    @Test
    @DisplayName("엣지 목록에 Map이 아닌 원소가 섞여 있어도 예외 없이 저장된다 — 채팅이 500이 되면 안 된다")
    void finalizeStream_nonMapEdgeElement_savedWithoutException() throws Exception {
        ChatAgentResponse resp = buildResponseWithEdges("""
            [
                "엣지가 아닌 원소",
                { "source": "node-a", "target": "node-b" },
                { "target": "node-b" }
            ]
            """);

        givenFinalizeStreamSession();
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, null, userId);

        ArgumentCaptor<String> edgesJson = ArgumentCaptor.forClass(String.class);
        verify(workflowCrudService).saveAgentVersion(eq(workflowId), any(), edgesJson.capture(), any(), any(), eq(false));
        List<Object> saved = objectMapper.readValue(edgesJson.getValue(), new TypeReference<>() {});
        // 비-Map 원소는 그대로 통과하고, Map 원소는 source/target이 없어도 id를 받는다.
        assertThat(saved).hasSize(3);
        assertThat(saved.get(0)).isEqualTo("엣지가 아닌 원소");
        assertThat(((Map<?, ?>) saved.get(1)).get("id")).isNotNull();
        assertThat(((Map<?, ?>) saved.get(2)).get("id")).isNotNull();
    }

    // ─────────────────── 노드 event 파생 (IEUM-BE-92) ───────────

    @Test
    @DisplayName("agent 노드에 event가 없으면 저장하지 않는다")
    void finalizeStream_missingEvent_notSaved() throws Exception {
        ChatAgentResponse resp = buildNodeResponse(
            "{ \"id\": \"node-x\", \"type\": \"HTTP\", \"config\": {} }");
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        assertThatThrownBy(() -> chatService.finalizeStream(workflowId, sessionId, resp, config, null, userId))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW));
        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("크레덴셜 주입은 agent가 보낸 type이 아니라 event로 계산한 type을 본다")
    @SuppressWarnings("unchecked")
    void finalizeStream_injectsByDerivedType() throws Exception {
        UUID fallbackCredentialId = UUID.randomUUID();
        ChatAgentResponse resp = buildNodeResponse("{ \"id\": \"node-ai\", \"event\": \"ai.agent\","
            + " \"type\": \"HTTP\", \"config\": { \"llmProvider\": \"CLAUDE\" } }");
        givenFinalizeStreamSession();
        given(credentialService.getByUserId(userId))
            .willReturn(List.of(buildCredential(fallbackCredentialId)));
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, fallbackCredentialId, userId);

        Map<String, Object> saved = captureSavedNodes().get(0);
        assertThat(saved).containsEntry("type", "AI");
        assertThat((Map<String, Object>) saved.get("config"))
            .containsEntry("credentialId", fallbackCredentialId.toString());
    }

    @Test
    @DisplayName("저장되는 노드는 파생값이다 — 비AI brand는 카탈로그 값, 옛 키(triggerType·serviceType)는 지운다")
    @SuppressWarnings("unchecked")
    void finalizeStream_savesDerivedNodes() throws Exception {
        ChatAgentResponse resp = buildNodeResponse("{ \"id\": \"node-http\", \"event\": \"http.request\","
            + " \"type\": \"HTTP\", \"config\": { \"method\": \"GET\", \"brand\": \"evil\","
            + " \"serviceType\": \"GOOGLE\", \"triggerType\": \"WEBHOOK\" } }");
        givenFinalizeStreamSession();
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, null, userId);

        Map<String, Object> saved = captureSavedNodes().get(0);
        assertThat(saved).containsEntry("event", "http.request");
        Map<String, Object> savedConfig = (Map<String, Object>) saved.get("config");
        assertThat(savedConfig).containsEntry("method", "GET")
            .containsEntry("brand", brandOf("http.request"))
            .doesNotContainKeys("serviceType", "triggerType");
    }

    @Test
    @DisplayName("agent 노드에 type이 없어도 event로 계산한다 — 저장 type AI·credentialId 주입, 응답 nodes도 파생 노드")
    @SuppressWarnings("unchecked")
    void finalizeStream_typeOmitted_derivedFromEvent() throws Exception {
        UUID fallbackCredentialId = UUID.randomUUID();
        ChatAgentResponse resp = buildNodeResponse("{ \"id\": \"node-ai\", \"event\": \"ai.agent\","
            + " \"config\": { \"llmProvider\": \"CLAUDE\", \"tools\": [ { \"name\": \"builtin:web_search\" } ] } }");
        givenFinalizeStreamSession();
        given(credentialService.getByUserId(userId))
            .willReturn(List.of(buildCredential(fallbackCredentialId)));
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        ChatResponse response =
            chatService.finalizeStream(workflowId, sessionId, resp, config, fallbackCredentialId, userId);

        Map<String, Object> saved = captureSavedNodes().get(0);
        assertThat(saved).containsEntry("type", "AI");
        assertThat((Map<String, Object>) saved.get("config"))
            .containsEntry("credentialId", fallbackCredentialId.toString());
        // FE는 응답 nodes를 type으로 거른다 — agent 원본(type 없음)이 아니라 저장한 파생 노드가 실린다
        assertThat(response.getNodes()).hasSize(1);
        assertThat((Map<String, Object>) response.getNodes().get(0))
            .containsEntry("type", "AI").containsEntry("event", "ai.agent");
    }

    @Test
    @DisplayName("currentNodes에 event가 없으면 INVALID_WORKFLOW — agent 호출·쿼터 예약 없음")
    void chat_currentNodesWithoutEvent_rejectedBeforeAgentCall() {
        givenChatSetup();
        ChatRequest request = buildRequestBeforeReserve("수정해줘", null);
        given(request.getCurrentNodes()).willReturn(List.of(Map.of("id", "n1", "type", "HTTP", "config", Map.of())));

        assertThatThrownBy(() -> chatService.chat(workflowId, userId, "ROLE_USER", request))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW));
        verify(betaPlatformProvider, never()).reserveQuota(any());
        verify(agentClient, never()).chat(any(AgentChatCallParams.class));
    }

    @Test
    @DisplayName("currentNodes 원소가 객체가 아니거나 null이면 INVALID_WORKFLOW(500 아님) — agent 호출·쿼터 예약 없음")
    void chat_currentNodesNotObject_rejectedBeforeAgentCall() {
        givenChatSetup();
        ChatRequest request = buildRequestBeforeReserve("수정해줘", null);
        given(request.getCurrentNodes()).willReturn(List.of("x"));

        assertThatThrownBy(() -> chatService.chat(workflowId, userId, "ROLE_USER", request))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW));
        verify(betaPlatformProvider, never()).reserveQuota(any());
        verify(agentClient, never()).chat(any(AgentChatCallParams.class));
    }

    @Test
    @DisplayName("currentNodes에 null 원소가 있으면 INVALID_WORKFLOW(500 아님) — agent 호출·쿼터 예약 없음")
    void chat_currentNodesNullElement_rejectedBeforeAgentCall() {
        givenChatSetup();
        ChatRequest request = buildRequestBeforeReserve("수정해줘", null);
        given(request.getCurrentNodes()).willReturn(java.util.Arrays.asList((Object) null));

        assertThatThrownBy(() -> chatService.chat(workflowId, userId, "ROLE_USER", request))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW));
        verify(betaPlatformProvider, never()).reserveQuota(any());
        verify(agentClient, never()).chat(any(AgentChatCallParams.class));
    }

    @Test
    @DisplayName("currentNodes는 파생된 모양으로 agent에 간다 — 옛 키 없음·type은 event가 정한다")
    @SuppressWarnings("unchecked")
    void chat_currentNodes_passedDerived() throws Exception {
        givenChatSetup();
        ChatRequest request = buildRequestBeforeReserve("수정해줘", null);
        given(request.getCurrentNodes()).willReturn(List.of(Map.of("id", "n1", "event", "http.request", "type", "AI",
            "config", Map.of("method", "GET", "serviceType", "GOOGLE", "triggerType", "MANUAL"))));
        given(agentClient.chat(any(AgentChatCallParams.class)))
            .willReturn(buildAgentResponse("CLARIFICATION_NEEDED", null));
        given(messageRepository.save(any()))
            .willReturn(buildMessage(buildSession(workflowId, userId), MessageType.AGENT, "응답"));

        chatService.chat(workflowId, userId, "ROLE_USER", request);

        ArgumentCaptor<AgentChatCallParams> params = ArgumentCaptor.forClass(AgentChatCallParams.class);
        verify(agentClient).chat(params.capture());
        Map<String, Object> sent = (Map<String, Object>) params.getValue().currentNodes().get(0);
        assertThat(sent).containsEntry("type", "HTTP").containsEntry("event", "http.request");
        assertThat((Map<String, Object>) sent.get("config")).containsEntry("method", "GET")
            .doesNotContainKeys("serviceType", "triggerType");
    }

    @Test
    @DisplayName("WS prepareStream도 currentNodes를 파생해 넘긴다")
    @SuppressWarnings("unchecked")
    void prepareStream_currentNodes_derived() {
        givenChatSetup();
        ChatRequest request = buildRequestBeforeReserve("수정해줘", null);
        given(request.getCurrentNodes()).willReturn(List.of(Map.of("id", "n1", "event", "http.request", "type", "AI",
            "config", Map.of())));

        ChatService.StreamSetupResult setup = chatService.prepareStream(workflowId, userId, "ROLE_USER", request);

        assertThat((Map<String, Object>) setup.currentNodes().get(0)).containsEntry("type", "HTTP");
    }

    /** chat()·prepareStream()이 agent 호출(스트림 준비) 직전까지 가는 데 필요한 스텁. */
    private void givenChatSetup() {
        WorkflowVersion version = buildVersionWithNodesJson("[]");   // given(...) 밖에서 만든다(중첩 스텁 금지)
        given(sessionRepository.save(any())).willReturn(buildSession(workflowId, userId));
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(betaPlatformProvider.isBetaEligible(userId)).willReturn(true);
        given(integrationContextService.resolve(userId))
            .willReturn(new IntegrationContext(List.of(), List.of()));
    }

    /** WORKFLOW_GENERATED 응답. 노드 JSON 조각(여러 개면 쉼표로 이어 붙인다)을 nodes 배열에 그대로 넣는다. */
    private ChatAgentResponse buildNodeResponse(String nodeJson) throws Exception {
        return objectMapper.readValue("""
            { "message": "완성됐어요", "type": "WORKFLOW_GENERATED", "nodes": [ %s ], "edges": [] }
            """.formatted(nodeJson), ChatAgentResponse.class);
    }

    /** saveAgentVersion에 넘어간 nodesJson을 캡처해 파싱한다. */
    private List<Map<String, Object>> captureSavedNodes() throws Exception {
        ArgumentCaptor<String> nodesJson = ArgumentCaptor.forClass(String.class);
        verify(workflowCrudService).saveAgentVersion(eq(workflowId), nodesJson.capture(), any(), any(), any(), eq(false));
        return objectMapper.readValue(nodesJson.getValue(), new TypeReference<>() {});
    }

    private static String brandOf(String event) {
        return CATALOG.app(CATALOG.event(event).orElseThrow().app()).orElseThrow().brand();
    }

    // ─────────────────── TRIGGER 노드 → 트리거 타입·cron 계산 (IEUM-AI-56, IEUM-BE-92) ───────────

    @Test
    @DisplayName("schedule.every 노드면 SCHEDULE과 cron 원문이 저장 경로로 넘어간다 — 변환은 저장 쪽이 한다")
    void finalizeStream_scheduleTrigger_passesTriggerTypeAndCron() throws Exception {
        ChatAgentResponse resp = buildNodeResponse("""
            { "id": "node-trigger", "event": "schedule.every", "type": "TRIGGER", "config": { "cron": "0 9 * * *" } }""");
        givenFinalizeStreamSession();
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, null, userId);

        verify(workflowCrudService).saveAgentVersion(
            eq(workflowId), any(), any(), eq(TriggerType.SCHEDULE), eq("0 9 * * *"), eq(false));
    }

    @Test
    @DisplayName("webhook.catch 노드면 WEBHOOK이 넘어간다 — 웹훅 호출이 트리거 타입 불일치로 막히지 않게")
    void finalizeStream_webhookTrigger_passesWebhook() throws Exception {
        ChatAgentResponse resp = buildNodeResponse("""
            { "id": "node-trigger", "event": "webhook.catch", "type": "TRIGGER", "config": {} }""");
        givenFinalizeStreamSession();
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, null, userId);

        verify(workflowCrudService).saveAgentVersion(
            eq(workflowId), any(), any(), eq(TriggerType.WEBHOOK), isNull(), eq(false));
    }

    @Test
    @DisplayName("TRIGGER 노드가 없으면 MANUAL·cron null이 넘어간다 — 기존 스케줄 유지 규칙은 없다(IEUM-BE-92)")
    void finalizeStream_noTriggerNode_passesManual() throws Exception {
        ChatAgentResponse resp = buildNodeResponse("""
            { "id": "node-http", "event": "http.request", "type": "HTTP", "config": { "method": "GET" } }""");
        givenFinalizeStreamSession();
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, null, userId);

        verify(workflowCrudService).saveAgentVersion(
            eq(workflowId), any(), any(), eq(TriggerType.MANUAL), isNull(), eq(false));
    }

    @Test
    @DisplayName("TRIGGER 노드가 여럿이면 첫 번째가 기준이다 — 실행과 같은 선택")
    void finalizeStream_multipleTriggers_firstWins() throws Exception {
        ChatAgentResponse resp = buildNodeResponse("""
            { "id": "node-hook", "event": "webhook.catch", "type": "TRIGGER", "config": {} },
            { "id": "node-cron", "event": "schedule.every", "type": "TRIGGER", "config": { "cron": "0 9 * * *" } }""");
        givenFinalizeStreamSession();
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, null, userId);

        verify(workflowCrudService).saveAgentVersion(
            eq(workflowId), any(), any(), eq(TriggerType.WEBHOOK), isNull(), eq(false));
    }

    @Test
    @DisplayName("TRIGGER 노드의 event를 카탈로그가 모르면 저장하지 않는다 — 메시지에 event·노드 id")
    void finalizeStream_unknownTriggerEvent_notSaved() throws Exception {
        ChatAgentResponse resp = buildNodeResponse("""
            { "id": "node-trigger", "event": "schedule.daily", "type": "TRIGGER", "config": {} }""");
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        assertThatThrownBy(() -> chatService.finalizeStream(workflowId, sessionId, resp, config, null, userId))
            .isInstanceOfSatisfying(CustomException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW);
                assertThat(e.getMessage()).contains("schedule.daily").contains("node-trigger");
            });
        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("schedule.every 노드에 cron이 없으면 INVALID_CRON_EXPRESSION으로 저장하지 않는다 — REST와 같은 문구")
    void finalizeStream_scheduleWithoutCron_notSaved() throws Exception {
        ChatAgentResponse resp = buildNodeResponse("""
            { "id": "node-trigger", "event": "schedule.every", "type": "TRIGGER", "config": {} }""");
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        assertThatThrownBy(() -> chatService.finalizeStream(workflowId, sessionId, resp, config, null, userId))
            .isInstanceOfSatisfying(CustomException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CRON_EXPRESSION);
                assertThat(e.getMessage()).isEqualTo("schedule.every 노드(node-trigger)의 config.cron이 필요합니다");
            });
        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any(), any(), any(), anyBoolean());
    }

    // ─────────────────── 대화형 빌드 중간 저장 buildState (IEUM-BE-106) ───────────

    @Test
    @DisplayName("BUILDING이면 cron 없는 schedule.every도 저장된다 — 트리거를 계산하지 않고 building=true로 넘긴다")
    void finalizeStream_building_scheduleWithoutCron_savedAsBuilding() throws Exception {
        ChatAgentResponse resp = buildStateResponse("BUILDING", """
            { "id": "node-trigger", "event": "schedule.every", "type": "TRIGGER", "config": {} }""");
        givenFinalizeStreamSession();
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, null, userId);

        verify(workflowCrudService).saveAgentVersion(eq(workflowId), any(), any(), isNull(), isNull(), eq(true));
    }

    @Test
    @DisplayName("BUILDING이어도 저장 가드는 돈다 — 노드 config.url이 웹훅 원문이면 저장하지 않는다")
    void finalizeStream_building_rawWebhookUrl_notSaved() throws Exception {
        ChatAgentResponse resp = buildStateResponse("BUILDING", """
            { "id": "node-http", "event": "http.request", "type": "HTTP", "config": { "method": "POST", "url": "%s" } }"""
            .formatted(SLACK_WEBHOOK));
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        assertThatThrownBy(() -> chatService.finalizeStream(workflowId, sessionId, resp, config, null, userId))
            .isInstanceOfSatisfying(CustomException.class, e ->
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW));
        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any(), any(), any(), anyBoolean());
    }

    @ParameterizedTest(name = "buildState={0}")
    @ValueSource(strings = {"DONE", "building"})
    @DisplayName("DONE이나 모르는 값이면 지금 경로 그대로 — 트리거를 계산해 building=false로 넘긴다")
    void finalizeStream_doneOrUnknownBuildState_computesTrigger(String buildState) throws Exception {
        ChatAgentResponse resp = buildStateResponse(buildState, """
            { "id": "node-trigger", "event": "schedule.every", "type": "TRIGGER", "config": { "cron": "0 9 * * *" } }""");
        givenFinalizeStreamSession();
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, null, userId);

        verify(workflowCrudService).saveAgentVersion(
            eq(workflowId), any(), any(), eq(TriggerType.SCHEDULE), eq("0 9 * * *"), eq(false));
    }

    @Test
    @DisplayName("DONE인데 WORKFLOW_MODIFIED가 아니면 저장하지 않고 WARN — 트리거가 조용히 안 켜지는 계약 위반을 로그로 드러낸다")
    void finalizeStream_doneWithoutWorkflowResult_warns() throws Exception {
        ChatAgentResponse resp = objectMapper.readValue("""
            { "message": "켜 둘게요", "type": "CLARIFICATION_NEEDED", "buildState": "DONE" }""", ChatAgentResponse.class);
        givenFinalizeStreamSession();
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", null, false);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(ChatService.class);
        logger.addAppender(appender);
        try {
            chatService.finalizeStream(workflowId, sessionId, resp, config, null, userId);
        } finally {
            logger.detachAppender(appender);
        }

        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any(), any(), any(), anyBoolean());
        assertThat(appender.list).anyMatch(e ->
            e.getLevel() == Level.WARN && e.getFormattedMessage().contains("buildState=DONE"));
    }

    /** buildState를 실은 WORKFLOW_MODIFIED 응답(대화형 빌드 엔진 모양). */
    private ChatAgentResponse buildStateResponse(String buildState, String nodeJson) throws Exception {
        return objectMapper.readValue("""
            { "message": "진행 중", "type": "WORKFLOW_MODIFIED", "buildState": "%s", "nodes": [ %s ], "edges": [] }
            """.formatted(buildState, nodeJson), ChatAgentResponse.class);
    }

    /** finalizeStream이 메시지를 저장하는 데 필요한 최소 스텁. */
    private void givenFinalizeStreamSession() {
        ChatSession session = buildSession(workflowId, userId);
        given(sessionRepository.findById(sessionId)).willReturn(Optional.of(session));
        given(messageRepository.save(any(ChatMessage.class)))
            .willReturn(buildMessage(session, MessageType.AGENT, "완성됐어요"));
    }

    /** saveAgentVersion에 넘어간 edgesJson을 캡처해 파싱한다. */
    private List<Map<String, Object>> captureSavedEdges() throws Exception {
        ArgumentCaptor<String> edgesJson = ArgumentCaptor.forClass(String.class);
        verify(workflowCrudService).saveAgentVersion(eq(workflowId), any(), edgesJson.capture(), any(), any(), eq(false));
        return objectMapper.readValue(edgesJson.getValue(), new TypeReference<>() {});
    }

    /** 노드 두 개 + 주어진 엣지 배열을 가진 WORKFLOW_GENERATED 응답. */
    private ChatAgentResponse buildResponseWithEdges(String edgesJson) throws Exception {
        String json = """
            {
                "message": "완성됐어요",
                "type": "WORKFLOW_GENERATED",
                "nodes": [
                    { "id": "node-a", "event": "http.request", "type": "HTTP", "config": {} },
                    { "id": "node-b", "event": "http.request", "type": "HTTP", "config": {} }
                ],
                "edges": %s
            }
            """.formatted(edgesJson);
        return objectMapper.readValue(json, ChatAgentResponse.class);
    }

    /** credentialId가 비어 있는 AI 노드 하나짜리 응답 — fallback 주입이 일어나는 모양이다. */
    private ChatAgentResponse buildAiNodeResponse() throws Exception {
        return objectMapper.readValue("""
            {
                "message": "완성됐어요",
                "type": "WORKFLOW_GENERATED",
                "nodes": [
                    { "id": "node-ai", "event": "ai.agent", "type": "AI", "config": { "llmProvider": "CLAUDE" } }
                ],
                "edges": []
            }
            """, ChatAgentResponse.class);
    }

    private Credential buildCredential(UUID credentialId) {
        return Credential.builder().id(credentialId).userId(userId).build();
    }

    /** HTTP 노드 하나짜리 WORKFLOW_GENERATED 응답. config만 테스트마다 바꾼다. */
    private ChatAgentResponse buildWorkflowResponse(String httpConfig) throws Exception {
        String json = """
            {
                "message": "완성됐어요",
                "type": "WORKFLOW_GENERATED",
                "workflowName": "알림 워크플로우",
                "nodes": [
                    { "id": "node-http", "event": "http.request", "type": "HTTP", "config": %s }
                ],
                "edges": []
            }
            """.formatted(httpConfig);
        return objectMapper.readValue(json, ChatAgentResponse.class);
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
     * reserveQuota 이전에 예외로 끝나는 시나리오용 — getCurrentNodes()는 reserveQuota 직전 파생에서,
     * getCurrentEdges()는 AgentChatCallParams 빌드 시점에 읽히고 미스텁이면 빈 리스트라 미리 스텁하면 strict
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
