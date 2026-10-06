package com.ieum.api.workflow.dto;

import static com.ieum.api.workflow.dto.WorkflowServiceType.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowServiceTypeTest {

    private NodeView node(String type, Map<String, Object> config) {
        return new NodeView("n", NodeType.valueOf(type), null, null, null, config);
    }

    @Test
    void 트리거_HTTP_AI도구에서_서비스를_중복없이_노드순서대로_추출한다() {
        List<NodeView> nodes = List.of(
            node("TRIGGER", Map.of()),
            node("AI", Map.of("tools", List.of(
                Map.of("name", "slack"),
                Map.of("name", "builtin:notion_create_page"),
                Map.of("name", "builtin:notion_read_page"),
                Map.of("name", "discord"),
                Map.of("name", "web_search")))),
            node("AI", Map.of("tools", List.of("builtin:github_create_issue", "builtin:google_gmail_send"))),
            node("HTTP", Map.of("url", "https://example.com"))
        );

        assertThat(WorkflowServiceType.extract(TriggerType.WEBHOOK, nodes))
            .containsExactly(WEBHOOK, SLACK, NOTION, DISCORD, GITHUB, GMAIL);
    }

    @Test
    void ieum_agent_구글_도구명을_매핑한다() {
        assertThat(WorkflowServiceType.extract(TriggerType.MANUAL, List.of(
            node("AI", Map.of("tools", List.of("gmail", "builtin:google_sheets_write", "builtin:google_drive_upload"))))))
            .containsExactly(GMAIL, GOOGLE_SHEETS, GOOGLE_DRIVE);
    }

    @Test
    void 연동_서비스가_없으면_빈_리스트() {
        assertThat(WorkflowServiceType.extract(TriggerType.MANUAL,
            List.of(node("AI", Map.of("prompt", "hi"))))).isEmpty();
    }
}
