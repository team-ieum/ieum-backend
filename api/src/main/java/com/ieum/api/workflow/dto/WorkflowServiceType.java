package com.ieum.api.workflow.dto;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
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

    /** AI 노드 config.tools[].name 접두사 → 서비스. AgentNodeExecutor/ToolAuthResolver의 도구 이름 규칙과 일치해야 한다. */
    private static final Map<String, WorkflowServiceType> TOOL_PREFIXES = Map.of(
        "slack", SLACK,
        "discord", DISCORD,
        "builtin:notion_", NOTION,
        "builtin:github_", GITHUB,
        "gmail", GMAIL, // ieum-agent 도구명
        "builtin:google_gmail", GMAIL,
        "builtin:google_sheets", GOOGLE_SHEETS,
        "builtin:google_drive", GOOGLE_DRIVE
    );

    /** 트리거 타입과 노드 정의에서 연동 서비스를 중복 없이 노드 순서대로 추출한다. */
    public static List<WorkflowServiceType> extract(TriggerType triggerType, List<NodeView> nodes) {
        Set<WorkflowServiceType> services = new LinkedHashSet<>();
        if (triggerType == TriggerType.WEBHOOK) {
            services.add(WEBHOOK);
        }
        for (NodeView node : nodes) {
            if (node.type() == NodeType.HTTP) {
                services.add(WEBHOOK);
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
