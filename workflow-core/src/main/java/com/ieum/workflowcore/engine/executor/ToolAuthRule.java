package com.ieum.workflowcore.engine.executor;

import java.util.Set;

/**
 * 도구 인증 규칙의 단일 출처 — tool_key(도구 이름)를 보고 실행 때 어떤 인증을 싣는지 정한다 (IEUM-BE-93).
 * {@link ToolCallPreparer}(Google 토큰·웹훅 URL)와 {@link ToolAuthResolver}(Notion·GitHub 헤더)가 이 상수로 판정하고,
 * api {@code CatalogStartupCheck}가 카탈로그 apps.json의 {@code auth}와 ACTION 이벤트 tool_key가 이 규칙으로 맞는지 기동 때 본다.
 *
 * <p>Google·Notion·GitHub는 접두사, slack·discord는 이름 정확 일치다. 채팅의 Google 토큰 판정({@code ChatService})도
 * {@link #GOOGLE_BUILTIN_PREFIX}를 쓴다. 목록 아이콘({@code WorkflowServiceType})의 비슷한 표는 인증이 아니라 표시용이라
 * 여기에 묶지 않는다.
 */
public final class ToolAuthRule {

    public static final String GOOGLE_OAUTH = "google_oauth";
    public static final String NOTION_TOKEN = "notion_token";
    public static final String GITHUB_TOKEN = "github_token";
    public static final String WEBHOOK_CREDENTIAL = "webhook_credential";
    /** apps.json {@code auth}에 올 수 있는 값. null(인증 없음)은 따로 허용한다 */
    public static final Set<String> KNOWN_AUTHS = Set.of(GOOGLE_OAUTH, NOTION_TOKEN, GITHUB_TOKEN, WEBHOOK_CREDENTIAL);

    public static final String GOOGLE_BUILTIN_PREFIX = "builtin:google_";
    public static final String NOTION_BUILTIN_PREFIX = "builtin:notion_";
    public static final String GITHUB_BUILTIN_PREFIX = "builtin:github_";
    /** 웹훅 URL을 주입받는 도구 — 접두사가 아니라 이름 정확 일치 */
    public static final Set<String> WEBHOOK_TOOL_NAMES = Set.of("slack", "discord");

    private ToolAuthRule() {
    }

    /** tool_key가 실행 때 받는 인증. 규칙 밖(null 포함)이면 null */
    public static String authOf(String toolKey) {
        if (toolKey == null) {
            return null;
        }
        if (toolKey.startsWith(GOOGLE_BUILTIN_PREFIX)) {
            return GOOGLE_OAUTH;
        }
        if (toolKey.startsWith(NOTION_BUILTIN_PREFIX)) {
            return NOTION_TOKEN;
        }
        if (toolKey.startsWith(GITHUB_BUILTIN_PREFIX)) {
            return GITHUB_TOKEN;
        }
        return WEBHOOK_TOOL_NAMES.contains(toolKey) ? WEBHOOK_CREDENTIAL : null;
    }
}
