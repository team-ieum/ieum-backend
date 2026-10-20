package com.ieum.api.workflow.dto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 워크플로우에 연동된 외부 서비스. 목록 UI의 서비스 아이콘/필터 뱃지 렌더링용.
 *
 * <p>JIRA, AIRTABLE, LINEAR는 프론트 명세상 정의만 되어 있고 현재 매핑되는 노드/도구가 없다.
 */
public enum WorkflowServiceType {
    SLACK, NOTION, GITHUB, GMAIL, GOOGLE_SHEETS, GOOGLE_DRIVE, JIRA, AIRTABLE, DISCORD, LINEAR, WEBHOOK;

    /** AI 노드의 event 앱 키. 이 앱의 노드만 config.tools로 아이콘을 정한다. */
    private static final String AI_APP = "ai";

    /**
     * 비AI 노드 event 앱 키({@code <앱>.<이벤트>}의 앱) → 서비스. 여기 없는 앱(google_calendar·schedule·
     * manual·branch·transform·approval)은 아이콘이 없다. apps.json brand는 이 enum과 1:1이 아니라 쓰지 않는다.
     */
    private static final Map<String, WorkflowServiceType> APP_SERVICES = Map.of(
        "gmail", GMAIL,
        "google_sheets", GOOGLE_SHEETS,
        "google_drive", GOOGLE_DRIVE,
        "notion", NOTION,
        "github", GITHUB,
        "slack", SLACK,
        "slack_bot", SLACK,
        "discord", DISCORD,
        "webhook", WEBHOOK,
        "http", WEBHOOK
    );

    /** AI 노드 config.tools[].name 접두사 → 서비스. AgentNodeExecutor/ToolAuthResolver의 도구 이름 규칙과 일치해야 한다. */
    private static final Map<String, WorkflowServiceType> TOOL_PREFIXES = Map.of(
        "slack", SLACK,
        "builtin:slack_", SLACK,
        "discord", DISCORD,
        "builtin:notion_", NOTION,
        "builtin:github_", GITHUB,
        "gmail", GMAIL, // ieum-agent 도구명
        "builtin:google_gmail", GMAIL,
        "builtin:google_sheets", GOOGLE_SHEETS,
        "builtin:google_drive", GOOGLE_DRIVE
    );

    /** 노드 event(비AI)와 AI 노드 도구에서 연동 서비스를 중복 없이 노드 순서대로 추출한다. */
    public static List<WorkflowServiceType> extract(List<NodeView> nodes) {
        Set<WorkflowServiceType> services = new LinkedHashSet<>();
        for (NodeView node : nodes) {
            String event = node.event();
            int dot = event == null ? -1 : event.indexOf('.');
            if (dot <= 0) {
                continue;
            }
            String app = event.substring(0, dot);
            if (!AI_APP.equals(app)) {
                WorkflowServiceType service = APP_SERVICES.get(app);
                if (service != null) services.add(service);
                continue;
            }
            if (node.config() != null && node.config().get("tools") instanceof List<?> tools) {
                for (Object tool : tools) {
                    Object name = tool instanceof Map<?, ?> map ? map.get("name") : tool;
                    if (name instanceof String toolName) {
                        TOOL_PREFIXES.forEach((prefix, type) -> {
                            if (toolName.startsWith(prefix)) services.add(type);
                        });
                    }
                }
            }
        }
        return List.copyOf(services);
    }
}
