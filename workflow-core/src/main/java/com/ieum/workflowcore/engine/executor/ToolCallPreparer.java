package com.ieum.workflowcore.engine.executor;

import com.ieum.workflowcore.engine.ExecutionCursor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ieum-agent로 도구를 보내기 전의 공통 전처리 — AI 노드({@code AgentNodeExecutor})와 ACTION 노드
 * ({@code ActionNodeExecutor})가 같이 쓴다. 순서는 ① {@code tools} 참조식 치환·정규화 ② Google 토큰
 * ③ Notion·GitHub 인증 헤더 ④ slack·discord 웹훅 URL 주입이다.
 *
 * <p>{@code renderDeep}이 만든 가변 복사본에서만 일한다 — 웹훅 URL 원문을 노드 원본에 넣으면 실행 중
 * 공유되는 노드 객체에 남는다. MCP 서버 해석은 AI 노드 전용이라 여기에 없다.
 */
@Slf4j
@Component
public class ToolCallPreparer {

    private static final String GOOGLE_BUILTIN_PREFIX = "builtin:google_";
    private static final Set<String> WEBHOOK_TOOL_NAMES = Set.of("slack", "discord");

    private final GoogleTokenProvider googleTokenProvider;
    private final ToolAuthResolver toolAuthResolver;
    private final WebhookCredentialProvider webhookCredentialProvider;

    public ToolCallPreparer(
        GoogleTokenProvider googleTokenProvider,
        ToolAuthResolver toolAuthResolver,
        WebhookCredentialProvider webhookCredentialProvider
    ) {
        this.googleTokenProvider = googleTokenProvider;
        this.toolAuthResolver = toolAuthResolver;
        this.webhookCredentialProvider = webhookCredentialProvider;
    }

    /**
     * 전처리 결과.
     *
     * @param tools             치환·정규화된 가변 복사본. 웹훅 URL이 주입돼 있다. 원본이 null이면 null
     * @param googleAccessToken Google 빌트인 도구가 있고 userId가 있을 때의 액세스 토큰, 아니면 null
     * @param authHeaders       {@code X-Notion-Token}·{@code X-GitHub-Token} 등 도구 인증 헤더(없으면 빈 Map)
     */
    public record PreparedTools(
        List<Map<String, Object>> tools,
        String googleAccessToken,
        Map<String, String> authHeaders
    ) {}

    /**
     * @param rawTools 노드 config의 {@code tools} 원문(치환 전). null이거나 List여야 한다
     * @param cursor   참조식 치환 기준 + userId 출처
     */
    public PreparedTools prepare(Object rawTools, ExecutionCursor cursor) {
        // 드롭다운으로 고른 리소스 ID(tools[].config.spreadsheet_id 등)의 참조식을 치환한 복사본(IEUM-BE-71).
        // 미해결 참조는 ""가 되어 agent 도구가 빈 ID를 에러로 거부한다. credentialId 자리의 참조식은 저장 시
        // NodeCredentialGuard가 막고 webhookCredentialId는 실행 시 소유권을 재확인해, 치환이 새 접근 경로를 만들지 않는다.
        List<Map<String, Object>> tools = parseTools(cursor.renderDeep(rawTools));
        UUID userId = cursor.getContext().getUserId();

        String googleAccessToken = resolveGoogleAccessToken(tools, userId);
        Map<String, String> authHeaders = toolAuthResolver.resolveHeaders(tools, userId);
        injectWebhookUrls(tools, userId);
        return new PreparedTools(tools, googleAccessToken, authHeaders);
    }

    /**
     * config.tools를 {@code List<Map<String, Object>>}로 변환한다.
     *
     * <p>LLM이 tools를 {@code ["web_search"]} 형태의 String 배열로 반환하는 경우
     * {@code [{"name": "web_search"}]} 형태의 Map 배열로 변환한다.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseTools(Object rawTools) {
        if (rawTools == null) return null;
        List<?> list = (List<?>) rawTools;
        if (list.isEmpty()) return new ArrayList<>();

        if (list.get(0) instanceof String) {
            return list.stream()
                .map(t -> (Map<String, Object>) Map.of("name", t))
                .collect(Collectors.toList());
        }
        return (List<Map<String, Object>>) rawTools;
    }

    /**
     * Google 빌트인 도구({@code builtin:google_*})가 tools 목록에 포함된 경우
     * Google Access Token을 조회하여 반환한다.
     *
     * <p>Google 도구가 없거나 userId가 없으면 {@code null}을 반환한다.
     * 토큰 조회 실패 시 예외를 전파하여 노드 실행 실패로 처리한다.
     */
    private String resolveGoogleAccessToken(List<Map<String, Object>> tools, UUID userId) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }

        boolean hasGoogleTool = tools.stream()
            .map(tool -> (String) tool.get("name"))
            .filter(name -> name != null)
            .anyMatch(name -> name.startsWith(GOOGLE_BUILTIN_PREFIX));

        if (!hasGoogleTool) {
            return null;
        }

        if (userId == null) {
            log.warn("[ToolCallPreparer] Google 빌트인 도구 사용이지만 userId가 없음 — 토큰 없이 진행");
            return null;
        }

        log.debug("[ToolCallPreparer] Google 빌트인 도구 감지 — userId: {} 로 토큰 조회", userId);
        return googleTokenProvider.getValidAccessToken(userId);
    }

    /**
     * slack/discord 도구의 {@code config.webhookCredentialId}로 복호화된 webhook URL을 조회해
     * 도구 config에 {@code webhook_url}을 in-place 주입한다.
     *
     * <p>웹훅 URL은 노드 config에 영속 저장하지 않고(노출 시 누구나 발송 가능), 실행 시점에만
     * 자격증명 저장소에서 채워 ieum-agent로 전달한다. agent의 {@code _bind_config}가 webhook_url을
     * 도구 인자로 바인딩하므로, 매 실행마다 URL 유실 없이 발송된다.
     *
     * <p>도구 형식: {@code {"name": "slack"|"discord", "config": {"webhookCredentialId": "<UUID>"}}}.
     */
    @SuppressWarnings("unchecked")
    private void injectWebhookUrls(List<Map<String, Object>> tools, UUID userId) {
        if (tools == null || tools.isEmpty() || userId == null) {
            return;
        }

        for (Map<String, Object> tool : tools) {
            if (!(tool.get("name") instanceof String name) || !WEBHOOK_TOOL_NAMES.contains(name)) {
                continue;
            }
            Object cfg = tool.get("config");
            if (!(cfg instanceof Map<?, ?> configMap)) {
                // config 값 자체는 싣지 않는다 — 사용자가 웹훅 URL을 붙여 넣었을 수 있다.
                log.warn("[ToolCallPreparer] webhook 도구 config가 Map이 아님 — name: {}", tool.get("name"));
                continue;
            }
            Object rawId = configMap.get("webhookCredentialId");
            if (rawId == null) {
                // 키 이름은 비밀이 아니라 그대로 남긴다 — 어떤 필드가 왔는지가 진단의 핵심이다.
                log.warn("[ToolCallPreparer] webhook 도구 config에 webhookCredentialId 없음 — config keys: {}",
                    configMap.keySet());
                continue;
            }
            UUID credentialId;
            try {
                credentialId = UUID.fromString(rawId.toString());
            } catch (IllegalArgumentException e) {
                // value를 싣지 않는다 — id 자리에 웹훅 URL을 붙여 넣은 경우가 정확히 이 분기로 온다.
                log.warn("[ToolCallPreparer] 잘못된 webhookCredentialId 형식 — name: {}", tool.get("name"));
                continue;
            }
            webhookCredentialProvider.resolveWebhookUrl(credentialId, userId)
                .ifPresentOrElse(
                    url -> {
                        Map<String, Object> mutableConfig = new HashMap<>((Map<String, Object>) configMap);
                        mutableConfig.put("webhook_url", url);
                        tool.put("config", mutableConfig);
                    },
                    () -> log.warn("[ToolCallPreparer] 웹훅 자격증명 미해결 — credentialId: {}", credentialId)
                );
        }
    }
}
