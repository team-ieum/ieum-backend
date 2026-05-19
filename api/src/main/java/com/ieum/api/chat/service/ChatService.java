package com.ieum.api.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.chat.dto.ChatAgentResponse;
import com.ieum.api.chat.dto.ChatRequest;
import com.ieum.api.chat.dto.ChatResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.chat.domain.ChatMessage;
import com.ieum.workflowcore.chat.domain.ChatSession;
import com.ieum.workflowcore.chat.domain.MessageType;
import com.ieum.workflowcore.chat.repository.ChatMessageRepository;
import com.ieum.workflowcore.chat.repository.ChatSessionRepository;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.engine.executor.CredentialProvider;
import com.ieum.workflowcore.engine.executor.GoogleTokenProvider;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워크플로우 기반 채팅 비즈니스 로직.
 *
 * <p>이 서비스는 LLM 기반 워크플로우 컴파일러 역할을 한다. 사용자의 자연어 입력을 받아
 * ieum-agent에 전달하고, agent의 응답에 따라 workflow canonical state를 DB에 저장한다.
 *
 * <h3>chat() 내부 흐름</h3>
 * <ol>
 *   <li>세션 찾기 or 신규 생성</li>
 *   <li>워크플로우 AI 노드 설정(credentialId, llmProvider, tools) 로드</li>
 *   <li>USER 메시지 DB 저장</li>
 *   <li>DB canonical state 조회 → currentNodes/currentEdges 결정</li>
 *   <li>연동 상태 분류 (availableIntegrations / unavailableIntegrations)</li>
 *   <li>Google 빌트인 도구 여부 판단 → Access Token 조회</li>
 *   <li>AgentClient.chat() 호출 (블로킹)</li>
 *   <li>응답 type에 따라 workflow 저장 or oauthUrl 주입</li>
 *   <li>AGENT 응답 메시지 DB 저장</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatService {

    private static final int MAX_TITLE_LENGTH = 50;
    private static final String GOOGLE_BUILTIN_PREFIX = "builtin:google_";

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final WorkflowCrudService workflowCrudService;
    private final CredentialProvider credentialProvider;
    private final GoogleTokenProvider googleTokenProvider;
    private final AgentClient agentClient;
    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────── REST 블로킹 ──────────────────────

    /**
     * 메시지를 전송하고 AI 응답을 블로킹으로 반환한다 (REST API 전용).
     *
     * @param workflowId 워크플로우 ID
     * @param userId     현재 인증된 사용자 ID
     * @param request    ChatRequest (prompt + optional currentNodes/currentEdges + optional sessionId)
     * @return AI AGENT 응답 메시지 DTO
     */
    @Transactional
    public ChatResponse chat(UUID workflowId, UUID userId, ChatRequest request) {

        // 1. 세션 찾거나 새로 생성
        ChatSession session = createOrGetSession(workflowId, userId, request.getSessionId());

        // 2. 워크플로우 AI 노드 설정 로드 (소유권 검증 포함)
        AgentConfig agentConfig = resolveAgentConfig(workflowId, userId);

        // 3. USER 메시지 저장
        saveUserMessage(session, request.getPrompt());

        // 4. 첫 메시지면 세션 제목 자동 설정
        if (session.getTitle() == null) {
            autoUpdateTitle(session, request.getPrompt());
        }

        // 5. Google 빌트인 도구 → Access Token 조회 (없으면 null)
        String googleAccessToken = resolveGoogleAccessToken(agentConfig.tools(), userId);

        // 6. AI 에이전트 호출
        log.info("[ChatService] AI 응답 요청 — workflowId: {}, sessionId: {}",
            workflowId, session.getId());
        ChatAgentResponse agentResponse = agentClient.chat(
            request.getPrompt(),
            request.getCurrentNodes(),
            request.getCurrentEdges(),
            List.of(),   // availableIntegrations — Step 3에서 구현
            List.of(),   // unavailableIntegrations — Step 3에서 구현
            agentConfig.llmProvider(),
            agentConfig.decryptedApiKey(),
            googleAccessToken,
            userId
        );

        // 7. AGENT 메시지 저장 후 반환 — sessionId를 직접 전달하여 LAZY 로딩 회피
        ChatMessage agentMessage = saveAgentMessage(
            session,
            agentResponse.getContent(),
            agentResponse.getInputTokens(),
            agentResponse.getOutputTokens()
        );

        return ChatResponse.from(agentMessage, session.getId());
    }

    // ─────────────────────────────────────── 히스토리 ─────────────────────────

    /**
     * 세션의 채팅 히스토리를 페이지네이션으로 조회한다 (최신순).
     *
     * @param sessionId 세션 ID
     * @param userId    현재 인증된 사용자 ID (소유권 검증용)
     * @param pageable  페이지 요청 정보
     * @return 페이지네이션된 ChatMessage 목록
     */
    public Page<ChatMessage> getChatHistory(UUID sessionId, UUID userId, Pageable pageable) {
        sessionRepository.findByIdAndUserId(sessionId, userId)
            .orElseThrow(() -> new CustomException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        return messageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, pageable);
    }

    // ─────────────────────────────────────── 세션 ─────────────────────────────

    /**
     * sessionId가 주어지면 해당 세션을 조회하고, 없으면 새 세션을 생성한다.
     * WebSocket 핸들러에서 직접 호출할 수 있도록 public으로 공개한다.
     *
     * <p><b>트랜잭션 참고:</b> 외부(다른 Bean)에서 호출 시 새 트랜잭션을 시작한다.
     * 같은 클래스 내의 {@code chat()}, {@code prepareStream()} 에서 호출 시에는
     * Spring AOP 셀프 인보케이션 제약으로 인해 상위 트랜잭션에 참여한다.
     *
     * @param workflowId 워크플로우 ID
     * @param userId     현재 인증된 사용자 ID
     * @param sessionId  기존 세션 ID (nullable)
     * @return 기존 또는 신규 ChatSession
     */
    @Transactional
    public ChatSession createOrGetSession(UUID workflowId, UUID userId, UUID sessionId) {
        if (sessionId != null) {
            return sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        }
        ChatSession session = ChatSession.builder()
            .workflowId(workflowId)
            .userId(userId)
            .build();
        return sessionRepository.save(session);
    }

    // ─────────────────────────────────────── 메시지 저장 ──────────────────────

    /**
     * USER 메시지를 DB에 저장한다.
     * WebSocket 핸들러에서 직접 호출할 수 있도록 public으로 공개한다.
     *
     * <p><b>트랜잭션 참고:</b> 외부 호출 시 독립 트랜잭션 시작,
     * 클래스 내부 호출 시 상위 트랜잭션에 참여한다 (셀프 인보케이션).
     */
    @Transactional
    public ChatMessage saveUserMessage(ChatSession session, String content) {
        ChatMessage message = ChatMessage.builder()
            .session(session)
            .senderType(MessageType.USER)
            .content(content)
            .build();
        return messageRepository.save(message);
    }

    /**
     * AGENT 응답 메시지를 DB에 저장한다.
     * WebSocket 핸들러에서 직접 호출할 수 있도록 public으로 공개한다.
     *
     * <p><b>트랜잭션 참고:</b> 외부 호출 시 독립 트랜잭션 시작,
     * 클래스 내부 호출 시 상위 트랜잭션에 참여한다 (셀프 인보케이션).
     */
    @Transactional
    public ChatMessage saveAgentMessage(ChatSession session, String content,
            Integer inputTokens, Integer outputTokens) {
        ChatMessage message = ChatMessage.builder()
            .session(session)
            .senderType(MessageType.AGENT)
            .content(content)
            .inputTokens(inputTokens)
            .outputTokens(outputTokens)
            .build();
        return messageRepository.save(message);
    }

    // ─────────────────────────────────────── 에이전트 설정 ────────────────────

    /**
     * 워크플로우에서 AI 노드 설정(llmProvider, credentialId, tools)을 추출하고
     * API Key를 복호화하여 반환한다.
     *
     * <p>WebSocket 핸들러에서 재사용할 수 있도록 public으로 공개한다.
     *
     * @throws CustomException WORKFLOW_NOT_FOUND — 워크플로우를 찾을 수 없는 경우
     * @throws CustomException WORKFLOW_HAS_NO_AI_NODE — AI 노드가 없는 경우
     * @throws CustomException INVALID_WORKFLOW — nodesJson 파싱 실패 시
     */
    @SuppressWarnings("unchecked")
    public AgentConfig resolveAgentConfig(UUID workflowId, UUID userId) {
        workflowCrudService.getWorkflowByOwner(userId, workflowId);

        WorkflowVersion version = workflowCrudService.findLatestVersion(workflowId)
            .orElseThrow(() -> new CustomException(ErrorCode.WORKFLOW_NOT_FOUND));

        List<Node> nodes;
        try {
            nodes = objectMapper.readValue(version.getNodesJson(), new TypeReference<>() {});
        } catch (Exception e) {
            log.error("[ChatService] nodesJson 파싱 실패 — workflowId: {}", workflowId, e);
            throw new CustomException(ErrorCode.INVALID_WORKFLOW);
        }

        Node aiNode = nodes.stream()
            .filter(n -> n.getType() == NodeType.AI)
            .findFirst()
            .orElseThrow(() -> new CustomException(ErrorCode.WORKFLOW_HAS_NO_AI_NODE));

        Map<String, Object> config = aiNode.getConfig();
        String llmProvider = (String) config.get("llmProvider");
        String credentialId = (String) config.get("credentialId");
        List<Map<String, Object>> tools = (List<Map<String, Object>>) config.get("tools");

        String decryptedApiKey = credentialProvider.getDecryptedApiKey(credentialId);

        return new AgentConfig(llmProvider, decryptedApiKey, tools);
    }

    // ─────────────────────────────────────── PRIVATE ──────────────────────────

    private void autoUpdateTitle(ChatSession session, String firstMessage) {
        String title = firstMessage.length() > MAX_TITLE_LENGTH
            ? firstMessage.substring(0, MAX_TITLE_LENGTH) + "..."
            : firstMessage;
        session.updateTitle(title);
        log.debug("[ChatService] 세션 제목 자동 설정 — sessionId: {}, title: {}",
            session.getId(), title);
    }

    private String resolveGoogleAccessToken(List<Map<String, Object>> tools, UUID userId) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        boolean hasGoogleTool = tools.stream()
            .map(t -> (String) t.get("name"))
            .filter(Objects::nonNull)
            .anyMatch(name -> name.startsWith(GOOGLE_BUILTIN_PREFIX));
        if (!hasGoogleTool) {
            return null;
        }
        log.debug("[ChatService] Google 빌트인 도구 감지 — userId: {} 로 토큰 조회", userId);
        return googleTokenProvider.getValidAccessToken(userId);
    }

    // ─────────────────────────────────────── WebSocket 스트리밍 ───────────────

    /**
     * WebSocket 스트리밍 시작 전 트랜잭션 처리 (세션 생성, 메시지 저장, 에이전트 설정 로드).
     *
     * <p>반환된 {@link StreamSetupResult}를 사용해 {@code AgentClient.chatStream()}을 호출한다.
     * 스트리밍이 끝난 뒤 {@link #saveAgentMessageById}로 AGENT 메시지를 저장한다.
     *
     * @param workflowId 워크플로우 ID
     * @param userId     현재 인증된 사용자 ID
     * @param request    ChatRequest (prompt + optional currentNodes/currentEdges + optional sessionId)
     * @return 스트리밍에 필요한 컨텍스트
     */
    @Transactional
    public StreamSetupResult prepareStream(UUID workflowId, UUID userId, ChatRequest request) {
        ChatSession session = createOrGetSession(workflowId, userId, request.getSessionId());
        AgentConfig config = resolveAgentConfig(workflowId, userId);

        saveUserMessage(session, request.getPrompt());

        if (session.getTitle() == null) {
            autoUpdateTitle(session, request.getPrompt());
        }

        String googleToken = resolveGoogleAccessToken(config.tools(), userId);

        log.info("[ChatService] 스트림 준비 완료 — workflowId: {}, sessionId: {}",
            workflowId, session.getId());

        return new StreamSetupResult(
            session.getId(),
            config,
            request.getPrompt(),
            request.getCurrentNodes(),
            request.getCurrentEdges(),
            googleToken
        );
    }

    /**
     * 스트리밍 완료 후 AGENT 메시지를 sessionId만으로 저장한다.
     *
     * <p>WebSocket 핸들러의 {@code onComplete} 콜백에서 호출한다. 이미 인증된 사용자의
     * 세션이므로 userId 재검증 없이 저장한다.
     *
     * @param sessionId    채팅 세션 ID
     * @param content      전체 응답 텍스트 (토큰 조각 누적)
     * @param inputTokens  입력 토큰 수 (nullable)
     * @param outputTokens 출력 토큰 수 (nullable)
     * @return 저장된 ChatMessage
     */
    @Transactional
    public ChatMessage saveAgentMessageById(UUID sessionId, String content,
            Integer inputTokens, Integer outputTokens) {
        ChatSession session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new CustomException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        return saveAgentMessage(session, content, inputTokens, outputTokens);
    }

    // ─────────────────────────────────────── 내부 DTO ─────────────────────────

    public record AgentConfig(
        String llmProvider,
        String decryptedApiKey,
        List<Map<String, Object>> tools
    ) {
        /** 로그/디버그 출력 시 복호화된 API Key가 노출되지 않도록 마스킹한다. */
        @Override
        public String toString() {
            return "AgentConfig[llmProvider=" + llmProvider
                + ", decryptedApiKey=***, tools=" + tools + "]";
        }
    }

    /**
     * WebSocket 스트리밍 설정 결과.
     * {@link #prepareStream}이 반환하며 {@code AgentClient.chatStream()} 호출에 사용된다.
     */
    public record StreamSetupResult(
        UUID sessionId,
        AgentConfig config,
        String prompt,
        List<Object> currentNodes,
        List<Object> currentEdges,
        String googleToken
    ) {}
}
