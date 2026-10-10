package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ieum.workflowcore.engine.ExecutionContext;
import com.ieum.workflowcore.engine.ExecutionCursor;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AI·ACTION 실행기가 공유하는 도구 전처리(렌더·토큰·인증 헤더·웹훅 URL)를 고정한다. */
class ToolCallPreparerTest {

    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T000/B000/secret-token";

    private GoogleTokenProvider googleTokenProvider;
    private NotionTokenProvider notionTokenProvider;
    private GitHubTokenProvider gitHubTokenProvider;
    private WebhookCredentialProvider webhookCredentialProvider;
    private ToolCallPreparer preparer;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        googleTokenProvider = mock(GoogleTokenProvider.class);
        notionTokenProvider = mock(NotionTokenProvider.class);
        gitHubTokenProvider = mock(GitHubTokenProvider.class);
        webhookCredentialProvider = mock(WebhookCredentialProvider.class);
        preparer = new ToolCallPreparer(
            googleTokenProvider,
            new ToolAuthResolver(mock(CredentialProvider.class), notionTokenProvider, gitHubTokenProvider),
            webhookCredentialProvider);
    }

    private ExecutionCursor cursor(UUID user) {
        ExecutionContext context = new ExecutionContext();
        context.setUserId(user);
        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setAllNodes(Collections.emptyList());
        cursor.setAllEdges(Collections.emptyList());
        cursor.setContext(context);
        return cursor;
    }

    private static Map<String, Object> tool(String name, Map<String, Object> config) {
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", name);
        tool.put("config", config);
        return tool;
    }

    @Test
    @DisplayName("tools가 없으면 빈 준비 결과 — 토큰·헤더·웹훅 조회를 하지 않는다")
    void nullTools_returnsEmptyPreparation() {
        ToolCallPreparer.PreparedTools prepared = preparer.prepare(null, cursor(userId));

        assertThat(prepared.tools()).isNull();
        assertThat(prepared.googleAccessToken()).isNull();
        assertThat(prepared.authHeaders()).isEmpty();
        verifyNoInteractions(googleTokenProvider, notionTokenProvider, gitHubTokenProvider,
            webhookCredentialProvider);
    }

    @Test
    @DisplayName("참조식을 치환한 가변 복사본을 돌려주고 원본 tools는 그대로 둔다")
    void rendersReferencesIntoMutableCopy() {
        ExecutionCursor cursor = cursor(userId);
        cursor.getContext().setNodeOutput("n", Map.of("title", "치환된 제목"));
        Map<String, Object> config = new HashMap<>(Map.of("title", "{{nodes.n.output.title}}"));
        List<Object> raw = List.of(tool("builtin:github_create_issue", config));

        ToolCallPreparer.PreparedTools prepared = preparer.prepare(raw, cursor);

        @SuppressWarnings("unchecked")
        Map<String, Object> renderedConfig = (Map<String, Object>) prepared.tools().get(0).get("config");
        assertThat(renderedConfig.get("title")).isEqualTo("치환된 제목");
        prepared.tools().add(new HashMap<>()); // 가변 복사본이어야 한다
        assertThat(config.get("title")).isEqualTo("{{nodes.n.output.title}}");
    }

    @Test
    @DisplayName("문자열 배열 tools는 {name} Map 배열로 바꾼다")
    void stringShorthandTools_becomeNameMaps() {
        ToolCallPreparer.PreparedTools prepared =
            preparer.prepare(List.of("web_search", "slack"), cursor(userId));

        assertThat(prepared.tools()).extracting(t -> t.get("name")).containsExactly("web_search", "slack");
    }

    @Test
    @DisplayName("Google 도구가 있고 userId가 있을 때만 토큰을 조회한다")
    void googleToken_onlyForGoogleToolsWithUser() {
        when(googleTokenProvider.getValidAccessToken(userId)).thenReturn("g-access");
        List<Object> googleTools = List.of(tool("builtin:google_sheets_read", new HashMap<>()));

        assertThat(preparer.prepare(googleTools, cursor(userId)).googleAccessToken()).isEqualTo("g-access");
        assertThat(preparer.prepare(googleTools, cursor(null)).googleAccessToken()).isNull();
        assertThat(preparer.prepare(List.of(tool("slack", new HashMap<>())), cursor(userId)).googleAccessToken())
            .isNull();
        verify(googleTokenProvider).getValidAccessToken(userId); // 위 세 호출 중 첫 번째만 조회했다
    }

    @Test
    @DisplayName("Notion·GitHub 도구는 연동 토큰을 각자의 인증 헤더로 준비한다")
    void notionAndGithubTools_resolveAuthHeaders() {
        when(notionTokenProvider.getAccessToken(userId)).thenReturn(Optional.of("n-token"));
        when(gitHubTokenProvider.getAccessToken(userId)).thenReturn(Optional.of("gh-token"));

        ToolCallPreparer.PreparedTools prepared = preparer.prepare(List.of(
            tool("builtin:notion_search", new HashMap<>()),
            tool("builtin:github_list_issues", new HashMap<>())), cursor(userId));

        assertThat(prepared.authHeaders())
            .containsEntry("X-Notion-Token", "n-token")
            .containsEntry("X-GitHub-Token", "gh-token");
    }

    @Test
    @DisplayName("slack·discord 도구에는 복호화한 webhook_url을 복사본에만 주입하고 원본 config는 그대로 둔다")
    void webhookUrl_injectedIntoCopyOnly() {
        UUID credentialId = UUID.randomUUID();
        when(webhookCredentialProvider.resolveWebhookUrl(credentialId, userId))
            .thenReturn(Optional.of(WEBHOOK_URL));
        Map<String, Object> config = new HashMap<>(Map.of("webhookCredentialId", credentialId.toString()));
        List<Object> raw = List.of(tool("slack", config));

        ToolCallPreparer.PreparedTools prepared = preparer.prepare(raw, cursor(userId));

        @SuppressWarnings("unchecked")
        Map<String, Object> injected = (Map<String, Object>) prepared.tools().get(0).get("config");
        assertThat(injected).containsEntry("webhook_url", WEBHOOK_URL)
            .containsEntry("webhookCredentialId", credentialId.toString());
        // 원문 URL이 노드 객체에 남으면 node_runs 입력 로그·조회 응답으로 샐 수 있다
        assertThat(config).isEqualTo(Map.of("webhookCredentialId", credentialId.toString()));
    }

    @Test
    @DisplayName("userId가 없거나 slack·discord가 아닌 도구에는 웹훅 조회를 하지 않는다")
    void webhookUrl_notResolvedWithoutUserOrForOtherTools() {
        String credentialId = UUID.randomUUID().toString();

        preparer.prepare(List.of(tool("slack", new HashMap<>(Map.of("webhookCredentialId", credentialId)))),
            cursor(null));
        preparer.prepare(List.of(tool("builtin:github_create_issue",
            new HashMap<>(Map.of("webhookCredentialId", credentialId)))), cursor(userId));

        verify(webhookCredentialProvider, never()).resolveWebhookUrl(any(), any());
    }

    @Test
    @DisplayName("name이 없는 도구는 웹훅 주입 대상이 아니다 — NPE 없이 그대로 둔다")
    void webhookUrl_toolWithoutName_isSkipped() {
        Map<String, Object> nameless = new HashMap<>();
        nameless.put("config", new HashMap<>(Map.of("webhookCredentialId", UUID.randomUUID().toString())));

        ToolCallPreparer.PreparedTools prepared = preparer.prepare(List.of(nameless), cursor(userId));

        assertThat(prepared.tools()).hasSize(1);
        verify(webhookCredentialProvider, never()).resolveWebhookUrl(any(), any());
    }
}
