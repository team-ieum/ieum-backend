package com.ieum.api.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.api.chat.dto.AgentAction;
import com.ieum.api.chat.dto.AgentResponseType;
import com.ieum.api.chat.dto.AvailableMcpServer;
import com.ieum.api.chat.dto.ChatAgentResponse;
import com.ieum.api.chat.dto.ChatRequest;
import com.ieum.api.chat.dto.ChatResponse;
import com.ieum.api.chat.dto.IntegrationInfo;
import com.ieum.api.chat.service.IntegrationContextService.IntegrationContext;
import com.ieum.api.credential.domain.Credential;
import com.ieum.api.credential.service.CredentialService;
import com.ieum.api.mcp.domain.McpServerCatalog;
import com.ieum.api.mcp.repository.McpServerCatalogRepository;
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
import com.ieum.workflowcore.engine.executor.GitHubTokenProvider;
import com.ieum.workflowcore.engine.executor.GoogleTokenProvider;
import com.ieum.workflowcore.engine.executor.NotionTokenProvider;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final CredentialService credentialService;
    private final GoogleTokenProvider googleTokenProvider;
    private final GitHubTokenProvider gitHubTokenProvider;
    private final NotionTokenProvider notionTokenProvider;
    private final IntegrationContextService integrationContextService;
    private final AgentClient agentClient;
    private final ObjectMapper objectMapper;
    private final McpServerCatalogRepository mcpServerCatalogRepository;

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
        AgentConfig agentConfig = resolveAgentConfig(workflowId, userId, request.getCredentialId());

        // 3. USER 메시지 저장
        saveUserMessage(session, request.getPrompt());

        // 4. 첫 메시지면 세션 제목 자동 설정
        if (session.getTitle() == null) {
            autoUpdateTitle(session, request.getPrompt());
        }

        // 5. 연동 서비스 상태 조회 (available / unavailable)
        IntegrationContext integrationContext = integrationContextService.resolve(userId);

        // 6. Google 빌트인 도구 → Access Token 조회 (없으면 null)
        String googleAccessToken = resolveGoogleAccessToken(agentConfig.tools(), userId);
        String githubToken = resolveGitHubAccessToken(integrationContext, userId);
        String notionToken = resolveNotionToken(integrationContext, userId);

        // 7. AI 에이전트 호출 (agent 스펙 미지원 provider 제거)
        log.info("[ChatService] AI 응답 요청 — workflowId: {}, sessionId: {}",
            workflowId, session.getId());
        List<IntegrationInfo> agentAvailable = filterAgentSupportedIntegrations(integrationContext.available());
        List<IntegrationInfo> agentUnavailable = filterAgentSupportedIntegrations(integrationContext.unavailable());
        List<AvailableMcpServer> availableMcpServers = resolveAvailableMcpServers(userId);
        ChatAgentResponse agentResponse = agentClient.chat(
            request.getPrompt(),
            request.getCurrentNodes(),
            request.getCurrentEdges(),
            agentAvailable,
            agentUnavailable,
            agentConfig.llmProvider(),
            agentConfig.decryptedApiKey(),
            googleAccessToken,
            githubToken,
            notionToken,
            availableMcpServers,
            userId
        );

        // 8. WORKFLOW_GENERATED/MODIFIED → DB에 새 버전으로 저장
        //    첫 생성 여부를 저장 전에 확인 (저장 후에는 version이 증가하므로)
        if (agentResponse.isWorkflowResult()) {
            int maxVersionBeforeSave = workflowCrudService.findMaxVersionByWorkflowId(workflowId);
            saveWorkflowVersion(workflowId, agentResponse, agentConfig, request.getCredentialId());

            if (maxVersionBeforeSave == 1 && agentResponse.getWorkflowName() != null) {
                workflowCrudService.updateWorkflowName(workflowId, agentResponse.getWorkflowName());
            }
        }

        // 9. INTEGRATION_REQUIRED → actions에 oauthUrl 주입
        if (agentResponse.getType() == AgentResponseType.INTEGRATION_REQUIRED) {
            injectOAuthUrls(agentResponse.getActions());
        }

        // 10. AGENT 메시지 저장 후 반환 — sessionId를 직접 전달하여 LAZY 로딩 회피
        ChatMessage agentMessage = saveAgentMessage(
            session,
            agentResponse.getContent(),
            agentResponse.getInputTokens(),
            agentResponse.getOutputTokens()
        );

        return ChatResponse.from(agentMessage, session.getId(), agentResponse);
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
    public AgentConfig resolveAgentConfig(UUID workflowId, UUID userId, UUID fallbackCredentialId) {
        workflowCrudService.getWorkflowByOwner(userId, workflowId);

        WorkflowVersion version = workflowCrudService.findLatestVersion(workflowId)
            .orElseThrow(() -> new CustomException(ErrorCode.WORKFLOW_NOT_FOUND));

        List<Node> nodes;
        try {
            WorkflowDefinitionDocument definition = workflowCrudService.loadDefinition(version);
            nodes = objectMapper.convertValue(definition.getNodes(), new TypeReference<>() {});
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            log.error("[ChatService] MongoDB 정의 로드 실패 — workflowId: {}", workflowId, e);
            throw new CustomException(ErrorCode.INVALID_WORKFLOW);
        }

        Node aiNode = nodes.stream()
            .filter(n -> n.getType() == NodeType.AI)
            .findFirst()
            .orElse(null);

        if (aiNode != null) {
            Map<String, Object> config = aiNode.getConfig();
            String llmProvider = (String) config.get("llmProvider");
            String credentialId = (String) config.get("credentialId");
            List<Map<String, Object>> tools = (List<Map<String, Object>>) config.get("tools");
            String decryptedApiKey = credentialProvider.getDecryptedApiKey(credentialId);
            return new AgentConfig(llmProvider, decryptedApiKey, tools);
        }

        // AI 노드 없음 → fallbackCredentialId로 기본 설정 사용 (빈 워크플로우 채팅 시)
        if (fallbackCredentialId == null) {
            throw new CustomException(ErrorCode.WORKFLOW_HAS_NO_AI_NODE);
        }
        log.info("[ChatService][DEBUG] fallbackCredentialId={}, userId={}", fallbackCredentialId, userId);
        Credential credential = credentialService.getByIdAndUserId(fallbackCredentialId, userId);
        String decryptedApiKey = credentialProvider.getDecryptedApiKey(fallbackCredentialId.toString());
        return new AgentConfig(credential.getProvider().name(), decryptedApiKey, null);
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

    /**
     * INTEGRATION_REQUIRED 응답의 actions에 oauthUrl을 주입한다.
     *
     * <p>ieum-agent는 OAuth 시작 URL을 알지 못하므로, backend가 응답 후처리 시 주입한다.
     * type이 "OAUTH"인 action에만 적용되며, 알 수 없는 provider는 oauthUrl을 null로 남긴다.
     *
     * @param actions AgentAction 목록 (nullable 허용 — null이면 무처리)
     */
    private void injectOAuthUrls(List<AgentAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        for (AgentAction action : actions) {
            if ("OAUTH".equals(action.getType()) && action.getProvider() != null) {
                action.setOauthUrl(resolveOAuthUrl(action.getProvider()));
            }
        }
    }

    /**
     * 프로바이더명으로 OAuth 시작 URL을 결정한다.
     *
     * <p>Spring Security의 OAuth2 authorizationEndpoint baseUri가
     * {@code /api/v1/oauth2/authorize}이므로 suffix로 프로바이더명(소문자)을 붙인다.
     *
     * @param provider 대소문자 무관 프로바이더명 (예: "GOOGLE", "google")
     * @return OAuth 시작 URL (미지원 프로바이더는 null)
     */
    private String resolveOAuthUrl(String provider) {
        return switch (provider.toUpperCase()) {
            case "GOOGLE" -> "/api/v1/oauth2/authorize/google";
            case "GITHUB" -> "/api/v1/github/oauth2/authorize";
            // TODO: NOTION OAuth 구현 후 추가
            default -> {
                log.warn("[ChatService] 알 수 없는 OAuth 프로바이더 — provider: {}", provider);
                yield null;
            }
        };
    }

    /**
     * AI가 생성/수정한 노드/엣지를 JSON으로 직렬화하여 새 워크플로우 버전으로 저장한다.
     *
     * <p>agent가 생성한 AI 노드에는 credentialId가 없으므로,
     * agentConfig/fallbackCredentialId를 기반으로 AI 노드 config에 주입한다.
     */
    @SuppressWarnings("unchecked")
    private void saveWorkflowVersion(UUID workflowId, ChatAgentResponse agentResponse,
            AgentConfig agentConfig, UUID fallbackCredentialId) {
        try {
            List<Map<String, Object>> nodes = objectMapper.convertValue(
                agentResponse.getNodes(), new TypeReference<>() {});

            // AI 노드에 credentialId / llmProvider 주입 (agent가 생성 시 누락하는 경우 보완)
            for (Map<String, Object> node : nodes) {
                String nodeType = (String) node.get("type");
                if ("AI".equals(nodeType)) {
                    Map<String, Object> config = (Map<String, Object>) node.get("config");
                    if (config != null) {
                        String existingCredentialId = (String) config.get("credentialId");
                        log.info("[ChatService][DEBUG] nodeId={}, existingCredentialId='{}', fallback={}",
                            node.get("id"), existingCredentialId, fallbackCredentialId);
                        if ((existingCredentialId == null || existingCredentialId.isBlank())
                                && fallbackCredentialId != null) {
                            config.put("credentialId", fallbackCredentialId.toString());
                            log.info("[ChatService][DEBUG] credentialId 주입 완료 — nodeId={}", node.get("id"));
                        }
                        String existingProvider = (String) config.get("llmProvider");
                        if ((existingProvider == null || existingProvider.isBlank())
                                && agentConfig.llmProvider() != null) {
                            config.put("llmProvider", agentConfig.llmProvider());
                        }
                    }
                }
            }

            String nodesJson = objectMapper.writeValueAsString(nodes);
            String edgesJson = objectMapper.writeValueAsString(agentResponse.getEdges());
            workflowCrudService.saveAgentVersion(workflowId, nodesJson, edgesJson);
            log.info("[ChatService] 워크플로우 버전 저장 완료 — workflowId: {}, type: {}",
                workflowId, agentResponse.getType());
        } catch (JsonProcessingException e) {
            log.error("[ChatService] 노드/엣지 직렬화 실패 — workflowId: {}", workflowId, e);
            throw new CustomException(ErrorCode.INVALID_WORKFLOW);
        }
    }

    /**
     * ieum-agent가 지원하지 않는 provider(GITHUB 등)를 필터링한다.
     * agent 스펙: GOOGLE, NOTION, SLACK, DISCORD만 허용
     */
    private static final Set<String> AGENT_SUPPORTED_PROVIDERS =
        Set.of("GOOGLE", "NOTION", "SLACK", "DISCORD");

    private List<IntegrationInfo> filterAgentSupportedIntegrations(List<IntegrationInfo> list) {
        return list.stream()
            .filter(info -> AGENT_SUPPORTED_PROVIDERS.contains(info.getProvider()))
            .toList();
    }

    private String resolveGitHubAccessToken(IntegrationContext integrationContext, UUID userId) {
        boolean isGitHubConnected = integrationContext.available().stream()
            .anyMatch(info -> "GITHUB".equals(info.getProvider()));
        if (!isGitHubConnected) {
            return null;
        }
        return gitHubTokenProvider.getAccessToken(userId).orElse(null);
    }

    private String resolveNotionToken(IntegrationContext integrationContext, UUID userId) {
        boolean isNotionConnected = integrationContext.available().stream()
            .anyMatch(info -> "NOTION".equals(info.getProvider()));
        if (!isNotionConnected) {
            return null;
        }
        return notionTokenProvider.getAccessToken(userId).orElse(null);
    }

    /**
     * 사용자가 보유한 활성(enabled) MCP 서버 카탈로그를 조회해 agent 생성 요청용 메타로 변환한다.
     *
     * <p>serverUrl/암호화 헤더 등 민감 정보는 제외하고 catalogId/name/description만 전달한다.
     * agent는 이 목록에 있는 catalogId만 노드의 mcp 도구로 허용한다(환각 차단).
     */
    private List<AvailableMcpServer> resolveAvailableMcpServers(UUID userId) {
        return mcpServerCatalogRepository.findByUserId(userId).stream()
            .filter(McpServerCatalog::isEnabled)
            .map(c -> new AvailableMcpServer(
                c.getId().toString(),
                c.getDisplayName(),
                c.getDescription()
            ))
            .toList();
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
        AgentConfig config = resolveAgentConfig(workflowId, userId, request.getCredentialId());

        saveUserMessage(session, request.getPrompt());

        if (session.getTitle() == null) {
            autoUpdateTitle(session, request.getPrompt());
        }

        IntegrationContext integrationContext = integrationContextService.resolve(userId);
        String googleToken = resolveGoogleAccessToken(config.tools(), userId);
        String githubToken = resolveGitHubAccessToken(integrationContext, userId);
        String notionToken = resolveNotionToken(integrationContext, userId);

        log.info("[ChatService] 스트림 준비 완료 — workflowId: {}, sessionId: {}",
            workflowId, session.getId());

        IntegrationContext agentContext = new IntegrationContext(
            filterAgentSupportedIntegrations(integrationContext.available()),
            filterAgentSupportedIntegrations(integrationContext.unavailable())
        );

        return new StreamSetupResult(
            session.getId(),
            config,
            request.getPrompt(),
            request.getCurrentNodes(),
            request.getCurrentEdges(),
            agentContext,
            googleToken,
            githubToken,
            notionToken,
            resolveAvailableMcpServers(userId)
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
        IntegrationContext integrationContext,
        String googleToken,
        String githubToken,
        String notionToken,
        List<AvailableMcpServer> availableMcpServers
    ) {}
}
