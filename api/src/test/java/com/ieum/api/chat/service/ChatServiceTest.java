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
import com.ieum.api.credential.domain.AiProvider;
import com.ieum.api.credential.domain.Credential;
import com.ieum.api.credential.service.CredentialService;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.catalog.NodeDerivation;
import com.ieum.workflowcore.chat.domain.ChatMessage;
import com.ieum.workflowcore.chat.domain.ChatSession;
import com.ieum.workflowcore.chat.domain.MessageType;
import com.ieum.workflowcore.chat.repository.ChatMessageRepository;
import com.ieum.workflowcore.chat.repository.ChatSessionRepository;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.executor.BetaPlatformProvider;
import com.ieum.workflowcore.engine.executor.CredentialProvider;
import com.ieum.workflowcore.service.WorkflowCrudService;
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
    @DisplayName("키가 하나도 없고 self-hosted·베타 자격도 없으면 CHAT_CREDENTIAL_REQUIRED 예외")
    void resolveAgentConfig_noCredential_notEligible_throwsChatCredentialRequired() {
        given(credentialService.getByUserId(userId))
            .willReturn(List.of());

        assertThatThrownBy(() ->
            chatService.resolveAgentConfig(workflowId, userId, null, "ROLE_USER")
        ).isInstanceOf(CustomException.class)
            .hasMessageContaining(ErrorCode.CHAT_CREDENTIAL_REQUIRED.getMessage());
    }

    @Test
    @DisplayName("키가 빈 AI 노드가 있어도 ROLE_USER는 최근 등록 키로 채팅한다 — 노드를 보지 않는다")
    void resolveAgentConfig_ignoresWorkflowAiNode_usesLatestCredential() {
        UUID latest = UUID.randomUUID();
        Credential credential = Mockito.mock(Credential.class);
        given(credential.getId()).willReturn(latest);
        given(credential.getProvider()).willReturn(AiProvider.OPENAI);
        given(credentialService.getByUserId(userId)).willReturn(List.of(credential));
        given(credentialService.getByIdAndUserId(latest, userId)).willReturn(credential);
        given(credentialProvider.getDecryptedApiKey(latest.toString(), userId)).willReturn("k");

        ChatService.AgentConfig result = chatService.resolveAgentConfig(workflowId, userId, null, "ROLE_USER");

        assertThat(result.llmProvider()).isEqualTo("OPENAI");
        assertThat(result.decryptedApiKey()).isEqualTo("k");
        assertThat(result.useBetaPlatformKey()).isFalse();
        verify(betaPlatformProvider, never()).isBetaEligible(any());
        verify(workflowCrudService, never()).findLatestVersion(any());
        verify(workflowCrudService, never()).loadDefinition(any());
    }

    @Test
    @DisplayName("요청 credentialId가 남의 것이면 소유 검증에서 막힌다")
    void resolveAgentConfig_foreignRequestCredential_rejected() {
        UUID foreign = UUID.randomUUID();
        given(credentialService.getByIdAndUserId(foreign, userId))
            .willThrow(new CustomException(ErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> chatService.resolveAgentConfig(workflowId, userId, foreign, "ROLE_USER"))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(credentialProvider, never()).getDecryptedApiKey(any(), any());
    }

    @Test
    @DisplayName("크레덴셜이 없어도 ROLE_TESTER는 키 없는 AgentConfig로 채팅할 수 있다 (자체 호스팅 LLM 경로)")
    void resolveAgentConfig_noCredential_selfHostedEligible() {
        given(credentialService.getByUserId(userId))
            .willReturn(List.of());

        ChatService.AgentConfig result = chatService.resolveAgentConfig(workflowId, userId, null, "ROLE_TESTER");

        assertThat(result.llmProvider()).isEqualTo("CLAUDE");
        assertThat(result.decryptedApiKey()).isNull();
    }

    @Test
    @DisplayName("크레덴셜이 없지만 베타 자격이면 platform 키 자격만 표시한다 (INCR 없음)")
    void resolveAgentConfig_noCredential_betaEligible() {
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
    @DisplayName("요청 credentialId가 null이고 등록된 크레덴셜이 있으면 그 키로 AgentConfig를 반환한다")
    void resolveAgentConfig_noRequestCredential_autoFallbackToUserCredential() {
        UUID autoCredentialId = UUID.randomUUID();
        Credential credential = Mockito.mock(Credential.class);

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

    // ─────────────────── finalizeStream 이름·베타 토큰 ─────────────────────

    @Test
    @DisplayName("후속 메시지(maxVersion > 1)이면 workflowName이 있어도 이름을 덮어쓰지 않는다")
    void finalizeStream_subsequentMessage_doesNotOverrideName() throws Exception {
        ChatAgentResponse resp = buildAgentResponse("WORKFLOW_GENERATED", "수정된 이름");
        given(workflowCrudService.findMaxVersionByWorkflowId(workflowId)).willReturn(2);
        givenFinalizeStreamSession();

        chatService.finalizeStream(workflowId, sessionId, resp, BYOK, userId);

        verify(workflowCrudService, never()).updateWorkflowName(any(), any());
    }

    @Test
    @DisplayName("agent가 workflowName을 null로 반환하면 이름 업데이트를 호출하지 않는다")
    void finalizeStream_workflowNameIsNull_doesNotUpdateName() throws Exception {
        ChatAgentResponse resp = buildAgentResponse("WORKFLOW_GENERATED", null);
        given(workflowCrudService.findMaxVersionByWorkflowId(workflowId)).willReturn(1);
        givenFinalizeStreamSession();

        chatService.finalizeStream(workflowId, sessionId, resp, BYOK, userId);

        verify(workflowCrudService, never()).updateWorkflowName(any(), any());
    }

    @Test
    @DisplayName("베타 platform 키 + totalTokens가 없으면 입출력 합산으로 recordTokens를 호출한다")
    void finalizeStream_betaPlatformKey_recordsSummedTokens() {
        ChatAgentResponse resp = clarificationWithUsage(null, 100, 50);
        givenFinalizeStreamSession();

        chatService.finalizeStream(workflowId, sessionId, resp, BETA, userId);

        verify(betaPlatformProvider).recordTokens(userId, 150L);
    }

    @Test
    @DisplayName("베타 platform 키 + 응답 usage가 null(모델이 토큰 미보고)이면 recordTokens를 호출하지 않는다")
    void finalizeStream_betaPlatformKey_skipsRecordTokensWhenUsageNull() throws Exception {
        ChatAgentResponse resp = buildAgentResponse("CLARIFICATION_NEEDED", null);
        givenFinalizeStreamSession();

        chatService.finalizeStream(workflowId, sessionId, resp, BETA, userId);

        verify(betaPlatformProvider, never()).recordTokens(any(), anyLong());
    }

    @Test
    @DisplayName("차감 기준은 입출력 합산이 아니라 agent가 보낸 totalTokens다")
    void recordTokens_usesAgentTotalTokens() {
        // 캐시드·reasoning 토큰이 있으면 total != prompt + completion이다
        ChatAgentResponse resp = clarificationWithUsage(900, 100, 50);
        givenFinalizeStreamSession();

        chatService.finalizeStream(workflowId, sessionId, resp, BETA, userId);

        verify(betaPlatformProvider).recordTokens(userId, 900L);
    }

    @Test
    @DisplayName("토큰 합계가 0이면 recordTokens를 호출하지 않는다 (무의미한 Redis 왕복 방지)")
    void recordTokens_skipsWhenZero() {
        ChatAgentResponse resp = clarificationWithUsage(0, null, null);
        givenFinalizeStreamSession();

        chatService.finalizeStream(workflowId, sessionId, resp, BETA, userId);

        verify(betaPlatformProvider, never()).recordTokens(any(), anyLong());
    }

    private static final ChatService.AgentConfig BYOK = new ChatService.AgentConfig("CLAUDE", "key", false);
    private static final ChatService.AgentConfig BETA = new ChatService.AgentConfig("GEMINI", null, true);

    /** 토큰 정보만 다른 CLARIFICATION_NEEDED 응답. 쓰지 않는 getter는 스텁하지 않는다(strict stubbing). */
    private ChatAgentResponse clarificationWithUsage(Integer total, Integer input, Integer output) {
        ChatAgentResponse resp = Mockito.mock(ChatAgentResponse.class);
        given(resp.isWorkflowResult()).willReturn(false);
        given(resp.getType()).willReturn(AgentResponseType.CLARIFICATION_NEEDED);
        given(resp.getContent()).willReturn("응답");
        given(resp.getTotalTokens()).willReturn(total);
        if (total == null || total <= 0) {
            given(resp.getInputTokens()).willReturn(input);
            given(resp.getOutputTokens()).willReturn(output);
        }
        return resp;
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
        ChatService.AgentConfig nonBeta = new ChatService.AgentConfig("CLAUDE", "key", false);

        String key = chatService.reserveBetaQuota(nonBeta, userId);

        assertThat(key).isNull();
        verify(betaPlatformProvider, never()).reserveQuota(any());
    }

    @Test
    @DisplayName("reserveBetaQuota — useBetaPlatformKey=true면 BetaPlatformProvider가 반환한 키를 그대로 반환한다")
    void reserveBetaQuota_betaConfig_returnsProviderKey() {
        ChatService.AgentConfig beta = new ChatService.AgentConfig("GEMINI", null, true);
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

        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        ChatResponse result = chatService.finalizeStream(
            workflowId, sessionId, resp, config, userId);

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

        ChatService.AgentConfig config = new ChatService.AgentConfig("GEMINI", null, true);

        TransactionSynchronizationManager.initSynchronization();
        try {
            chatService.finalizeStream(workflowId, sessionId, resp, config, userId);

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

        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        ChatResponse result = chatService.finalizeStream(
            workflowId, sessionId, resp, config, userId);

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
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        assertThatThrownBy(() -> chatService.finalizeStream(
            workflowId, sessionId, resp, config, userId))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_WORKFLOW));

        // 거부는 저장 경로에 닿기 전에 끝나야 한다 — 새 버전이 남으면 막은 의미가 없다.
        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any(), any(), any(), anyBoolean());
        verify(workflowCrudService, never()).updateWorkflowName(any(), any());
        verify(messageRepository, never()).save(any(ChatMessage.class));
    }

    @Test
    @DisplayName("거부 메시지는 대안을 알려주고 URL을 되싣지 않는다 — REST 경로와 같은 문구")
    void agentRejectionMessage_guidesToCredential_withoutEchoingUrl() throws Exception {
        ChatAgentResponse resp = buildWorkflowResponse(
            "{ \"method\": \"POST\", \"url\": \"" + SLACK_WEBHOOK + "\" }");
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        assertThatThrownBy(() -> chatService.finalizeStream(
            workflowId, sessionId, resp, config, userId))
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
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, userId);

        verify(workflowCrudService).saveAgentVersion(eq(workflowId), any(), any(), any(), any(), eq(false));
    }

    // ─────────────────── agent 생성 정의의 남의 credentialId 거부 (IEUM-BE-64) ─

    @Test
    @DisplayName("finalizeStream — agent가 만든 노드에 남의 credentialId가 있으면 저장하지 않는다")
    void finalizeStream_agentNodeWithForeignCredentialId_notSaved() throws Exception {
        UUID foreignCredentialId = UUID.randomUUID();
        ChatAgentResponse resp = buildWorkflowResponse(
            "{ \"credentialId\": \"" + foreignCredentialId + "\" }");
        given(credentialService.getByUserId(userId)).willReturn(List.of());

        assertThatThrownBy(() -> chatService.finalizeStream(workflowId, sessionId, resp, BYOK, userId))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_WORKFLOW))
            // 메시지는 WS 핸들러가 사용자에게 그대로 보내고 로그에도 남는다.
            .satisfies(e -> assertThat(e.getMessage())
                .doesNotContain(foreignCredentialId.toString()));

        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("빈 credentialId AI 노드는 빈 채로 저장된다 — 요청 키·채팅 제공사를 주입하지 않는다")
    @SuppressWarnings("unchecked")
    void finalizeStream_emptyCredentialAiNode_savedEmpty() throws Exception {
        // 빌더가 모델 카드로 키·제공사·모델을 함께 쓴다(IEUM-AI-85). 채팅 제공사(OPENAI)와 노드 제공사가
        // 달라도 요청 키를 채우면 제공사가 엇갈린 키가 들어간다.
        UUID requestCredentialId = UUID.randomUUID();
        ChatAgentResponse resp = buildNodeResponse(
            "{ \"id\": \"node-ai\", \"event\": \"ai.agent\", \"type\": \"AI\", \"config\": { \"credentialId\": \"\" } }");
        givenFinalizeStreamSession();
        given(credentialService.getByUserId(userId))
            .willReturn(List.of(buildCredential(requestCredentialId)));
        ChatService.AgentConfig config = new ChatService.AgentConfig("OPENAI", "key", false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, userId);

        Map<String, Object> savedConfig = (Map<String, Object>) captureSavedNodes().get(0).get("config");
        assertThat(savedConfig).containsEntry("credentialId", "").doesNotContainKey("llmProvider");
    }

    @Test
    @DisplayName("agent 응답 AI 노드가 남의 credentialId를 직접 실으면 저장하지 않는다")
    void foreignCredentialAiNodeFromAgent_rejected() throws Exception {
        // agent가 실어 보낸 남의 키는 저장 가드가 막는다(IEUM-BE-64).
        UUID foreignCredentialId = UUID.randomUUID();
        ChatAgentResponse resp = buildAiNodeResponse(foreignCredentialId.toString());

        given(credentialService.getByUserId(userId))
            .willReturn(List.of(buildCredential(UUID.randomUUID())));
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        assertThatThrownBy(() -> chatService.finalizeStream(
            workflowId, sessionId, resp, config, userId))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_WORKFLOW))
            .satisfies(e -> assertThat(e.getMessage())
                .doesNotContain(foreignCredentialId.toString()));

        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("finalizeStream — agent가 만든 노드 config에 API 키 원문이 있으면 저장하지 않는다")
    void finalizeStream_agentNodeWithInlineSecret_notSaved() throws Exception {
        // 사용자가 프롬프트에 키를 그대로 적으면 그 값이 노드 config로 돌아온다. config는 Mongo에
        // 평문 저장되고 조회 응답에 그대로 실린다.
        ChatAgentResponse resp = buildWorkflowResponse("{ \"apiKey\": \"sk-live-not-a-real-key\" }");
        given(credentialService.getByUserId(userId)).willReturn(List.of());

        assertThatThrownBy(() -> chatService.finalizeStream(workflowId, sessionId, resp, BYOK, userId))
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
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, userId);

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
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, userId);

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
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, userId);

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
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        assertThatThrownBy(() -> chatService.finalizeStream(workflowId, sessionId, resp, config, userId))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW));
        verify(workflowCrudService, never()).saveAgentVersion(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("저장 type은 agent가 보낸 type이 아니라 event로 계산한 값이다 — 빈 credentialId는 빈 채로")
    @SuppressWarnings("unchecked")
    void finalizeStream_savesDerivedType() throws Exception {
        UUID fallbackCredentialId = UUID.randomUUID();
        ChatAgentResponse resp = buildNodeResponse("{ \"id\": \"node-ai\", \"event\": \"ai.agent\","
            + " \"type\": \"HTTP\", \"config\": { \"llmProvider\": \"CLAUDE\" } }");
        givenFinalizeStreamSession();
        given(credentialService.getByUserId(userId))
            .willReturn(List.of(buildCredential(fallbackCredentialId)));
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, userId);

        Map<String, Object> saved = captureSavedNodes().get(0);
        assertThat(saved).containsEntry("type", "AI");
        assertThat((Map<String, Object>) saved.get("config")).doesNotContainKey("credentialId");
    }

    @Test
    @DisplayName("저장되는 노드는 파생값이다 — 비AI brand는 카탈로그 값, 옛 키(triggerType·serviceType)는 지운다")
    @SuppressWarnings("unchecked")
    void finalizeStream_savesDerivedNodes() throws Exception {
        ChatAgentResponse resp = buildNodeResponse("{ \"id\": \"node-http\", \"event\": \"http.request\","
            + " \"type\": \"HTTP\", \"config\": { \"method\": \"GET\", \"brand\": \"evil\","
            + " \"serviceType\": \"GOOGLE\", \"triggerType\": \"WEBHOOK\" } }");
        givenFinalizeStreamSession();
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, userId);

        Map<String, Object> saved = captureSavedNodes().get(0);
        assertThat(saved).containsEntry("event", "http.request");
        Map<String, Object> savedConfig = (Map<String, Object>) saved.get("config");
        assertThat(savedConfig).containsEntry("method", "GET")
            .containsEntry("brand", brandOf("http.request"))
            .doesNotContainKeys("serviceType", "triggerType");
    }

    @Test
    @DisplayName("agent 노드에 type이 없어도 event로 계산한다 — 저장 type AI·credentialId 빈 채, 응답 nodes도 파생 노드")
    @SuppressWarnings("unchecked")
    void finalizeStream_typeOmitted_derivedFromEvent() throws Exception {
        UUID fallbackCredentialId = UUID.randomUUID();
        ChatAgentResponse resp = buildNodeResponse("{ \"id\": \"node-ai\", \"event\": \"ai.agent\","
            + " \"config\": { \"llmProvider\": \"CLAUDE\", \"tools\": [ { \"name\": \"builtin:web_search\" } ] } }");
        givenFinalizeStreamSession();
        given(credentialService.getByUserId(userId))
            .willReturn(List.of(buildCredential(fallbackCredentialId)));
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        ChatResponse response =
            chatService.finalizeStream(workflowId, sessionId, resp, config, userId);

        Map<String, Object> saved = captureSavedNodes().get(0);
        assertThat(saved).containsEntry("type", "AI");
        assertThat((Map<String, Object>) saved.get("config")).doesNotContainKey("credentialId");
        // FE는 응답 nodes를 type으로 거른다 — agent 원본(type 없음)이 아니라 저장한 파생 노드가 실린다
        assertThat(response.getNodes()).hasSize(1);
        assertThat((Map<String, Object>) response.getNodes().get(0))
            .containsEntry("type", "AI").containsEntry("event", "ai.agent");
    }

    @Test
    @DisplayName("currentNodes에 event가 없으면 INVALID_WORKFLOW")
    void prepareStream_currentNodesWithoutEvent_rejected() {
        givenChatSetup();
        ChatRequest request = buildStreamRequest("수정해줘", null);
        given(request.getCurrentNodes()).willReturn(List.of(Map.of("id", "n1", "type", "HTTP", "config", Map.of())));

        assertThatThrownBy(() -> chatService.prepareStream(workflowId, userId, "ROLE_USER", request))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW));
    }

    @Test
    @DisplayName("currentNodes 원소가 객체가 아니면 INVALID_WORKFLOW(500 아님)")
    void prepareStream_currentNodesNotObject_rejected() {
        givenChatSetup();
        ChatRequest request = buildStreamRequest("수정해줘", null);
        given(request.getCurrentNodes()).willReturn(List.of("x"));

        assertThatThrownBy(() -> chatService.prepareStream(workflowId, userId, "ROLE_USER", request))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW));
    }

    @Test
    @DisplayName("currentNodes에 null 원소가 있으면 INVALID_WORKFLOW(500 아님)")
    void prepareStream_currentNodesNullElement_rejected() {
        givenChatSetup();
        ChatRequest request = buildStreamRequest("수정해줘", null);
        given(request.getCurrentNodes()).willReturn(java.util.Arrays.asList((Object) null));

        assertThatThrownBy(() -> chatService.prepareStream(workflowId, userId, "ROLE_USER", request))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW));
    }

    @Test
    @DisplayName("prepareStream은 currentNodes를 파생해 넘긴다 — 옛 키 없음·type은 event가 정한다")
    @SuppressWarnings("unchecked")
    void prepareStream_currentNodes_derived() {
        givenChatSetup();
        ChatRequest request = buildStreamRequest("수정해줘", null);
        given(request.getCurrentNodes()).willReturn(List.of(Map.of("id", "n1", "event", "http.request", "type", "AI",
            "config", Map.of("method", "GET", "serviceType", "GOOGLE", "triggerType", "MANUAL"))));

        ChatService.StreamSetupResult setup = chatService.prepareStream(workflowId, userId, "ROLE_USER", request);

        Map<String, Object> sent = (Map<String, Object>) setup.currentNodes().get(0);
        assertThat(sent).containsEntry("type", "HTTP").containsEntry("event", "http.request");
        assertThat((Map<String, Object>) sent.get("config")).containsEntry("method", "GET")
            .doesNotContainKeys("serviceType", "triggerType");
    }

    /** prepareStream()이 스트림 준비를 끝내는 데 필요한 스텁. */
    private void givenChatSetup() {
        given(sessionRepository.save(any())).willReturn(buildSession(workflowId, userId));
        given(credentialService.getByUserId(userId)).willReturn(List.of());
        given(betaPlatformProvider.isBetaEligible(userId)).willReturn(true);
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
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, userId);

        verify(workflowCrudService).saveAgentVersion(
            eq(workflowId), any(), any(), eq(TriggerType.SCHEDULE), eq("0 9 * * *"), eq(false));
    }

    @Test
    @DisplayName("webhook.catch 노드면 WEBHOOK이 넘어간다 — 웹훅 호출이 트리거 타입 불일치로 막히지 않게")
    void finalizeStream_webhookTrigger_passesWebhook() throws Exception {
        ChatAgentResponse resp = buildNodeResponse("""
            { "id": "node-trigger", "event": "webhook.catch", "type": "TRIGGER", "config": {} }""");
        givenFinalizeStreamSession();
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, userId);

        verify(workflowCrudService).saveAgentVersion(
            eq(workflowId), any(), any(), eq(TriggerType.WEBHOOK), isNull(), eq(false));
    }

    @Test
    @DisplayName("TRIGGER 노드가 없으면 MANUAL·cron null이 넘어간다 — 기존 스케줄 유지 규칙은 없다(IEUM-BE-92)")
    void finalizeStream_noTriggerNode_passesManual() throws Exception {
        ChatAgentResponse resp = buildNodeResponse("""
            { "id": "node-http", "event": "http.request", "type": "HTTP", "config": { "method": "GET" } }""");
        givenFinalizeStreamSession();
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, userId);

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
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, userId);

        verify(workflowCrudService).saveAgentVersion(
            eq(workflowId), any(), any(), eq(TriggerType.WEBHOOK), isNull(), eq(false));
    }

    @Test
    @DisplayName("TRIGGER 노드의 event를 카탈로그가 모르면 저장하지 않는다 — 메시지에 event·노드 id")
    void finalizeStream_unknownTriggerEvent_notSaved() throws Exception {
        ChatAgentResponse resp = buildNodeResponse("""
            { "id": "node-trigger", "event": "schedule.daily", "type": "TRIGGER", "config": {} }""");
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        assertThatThrownBy(() -> chatService.finalizeStream(workflowId, sessionId, resp, config, userId))
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
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        assertThatThrownBy(() -> chatService.finalizeStream(workflowId, sessionId, resp, config, userId))
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
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, userId);

        verify(workflowCrudService).saveAgentVersion(eq(workflowId), any(), any(), isNull(), isNull(), eq(true));
    }

    @Test
    @DisplayName("BUILDING이어도 저장 가드는 돈다 — 노드 config.url이 웹훅 원문이면 저장하지 않는다")
    void finalizeStream_building_rawWebhookUrl_notSaved() throws Exception {
        ChatAgentResponse resp = buildStateResponse("BUILDING", """
            { "id": "node-http", "event": "http.request", "type": "HTTP", "config": { "method": "POST", "url": "%s" } }"""
            .formatted(SLACK_WEBHOOK));
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        assertThatThrownBy(() -> chatService.finalizeStream(workflowId, sessionId, resp, config, userId))
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
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);

        chatService.finalizeStream(workflowId, sessionId, resp, config, userId);

        verify(workflowCrudService).saveAgentVersion(
            eq(workflowId), any(), any(), eq(TriggerType.SCHEDULE), eq("0 9 * * *"), eq(false));
    }

    @Test
    @DisplayName("DONE인데 WORKFLOW_MODIFIED가 아니면 저장하지 않고 WARN — 트리거가 조용히 안 켜지는 계약 위반을 로그로 드러낸다")
    void finalizeStream_doneWithoutWorkflowResult_warns() throws Exception {
        ChatAgentResponse resp = objectMapper.readValue("""
            { "message": "켜 둘게요", "type": "CLARIFICATION_NEEDED", "buildState": "DONE" }""", ChatAgentResponse.class);
        givenFinalizeStreamSession();
        ChatService.AgentConfig config = new ChatService.AgentConfig("CLAUDE", "key", false);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(ChatService.class);
        logger.addAppender(appender);
        try {
            chatService.finalizeStream(workflowId, sessionId, resp, config, userId);
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

    /** config.credentialId를 실은 AI 노드 하나짜리 응답. */
    private ChatAgentResponse buildAiNodeResponse(String credentialId) throws Exception {
        return objectMapper.readValue("""
            {
                "message": "완성됐어요",
                "type": "WORKFLOW_GENERATED",
                "nodes": [
                    { "id": "node-ai", "event": "ai.agent", "type": "AI",
                      "config": { "llmProvider": "CLAUDE", "credentialId": "%s" } }
                ],
                "edges": []
            }
            """.formatted(credentialId), ChatAgentResponse.class);
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

    /**
     * prepareStream 요청 — getCurrentNodes()는 테스트마다 스텁하고, getCurrentEdges()는 미스텁(null)으로 둔다.
     * 읽히지 않는 값을 미리 스텁하면 strict stubbing에서 미사용으로 실패한다.
     */
    private ChatRequest buildStreamRequest(String prompt, UUID credentialId) {
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
}
