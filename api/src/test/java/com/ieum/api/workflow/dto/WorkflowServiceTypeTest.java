package com.ieum.api.workflow.dto;

import static com.ieum.api.workflow.dto.WorkflowServiceType.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.domain.enums.NodeType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowServiceTypeTest {

    private NodeView node(NodeType type, String event, Map<String, Object> config) {
        return new NodeView("n", type, null, null, null, config, event);
    }

    @Test
    void 웹훅트리거_AI도구_HTTP에서_서비스를_중복없이_노드순서대로_추출한다() {
        List<NodeView> nodes = List.of(
            node(NodeType.TRIGGER, "webhook.catch", Map.of()),
            node(NodeType.AI, "ai.agent", Map.of("tools", List.of(
                Map.of("name", "slack"),
                Map.of("name", "builtin:notion_create_page"),
                Map.of("name", "builtin:notion_read_page"),
                Map.of("name", "discord"),
                Map.of("name", "web_search")))),
            node(NodeType.AI, "ai.agent", Map.of("tools", List.of("builtin:github_create_issue", "builtin:google_gmail_send"))),
            node(NodeType.HTTP, "http.request", Map.of("url", "https://example.com"))
        );

        assertThat(WorkflowServiceType.extract(nodes))
            .containsExactly(WEBHOOK, SLACK, NOTION, DISCORD, GITHUB, GMAIL);
    }

    @Test
    void ACTION과_앱트리거는_event_앱키로_추출한다() {
        assertThat(WorkflowServiceType.extract(List.of(
            node(NodeType.TRIGGER, "gmail.new_email", Map.of()),
            node(NodeType.ACTION, "github.create_issue", Map.of("owner", "o")),
            node(NodeType.ACTION, "slack.send_message", Map.of()),
            node(NodeType.ACTION, "google_sheets.append", Map.of()),
            node(NodeType.ACTION, "google_drive.upload", Map.of()),
            node(NodeType.ACTION, "notion.create_page", Map.of()),
            node(NodeType.ACTION, "discord.send_message", Map.of()))))
            .containsExactly(GMAIL, GITHUB, SLACK, GOOGLE_SHEETS, GOOGLE_DRIVE, NOTION, DISCORD);
    }

    @Test
    void github_트리거에도_아이콘이_붙는다() {
        assertThat(WorkflowServiceType.extract(List.of(
            node(NodeType.TRIGGER, "github.new_issue", Map.of()))))
            .containsExactly(GITHUB);
    }

    @Test
    void 아이콘이_없는_앱은_brand가_있어도_비운다() {
        assertThat(WorkflowServiceType.extract(List.of(
            node(NodeType.TRIGGER, "schedule.every", Map.of("cron", "0 9 * * *", "brand", "webhook")),
            node(NodeType.TRIGGER, "manual.run", Map.of("brand", "webhook")),
            node(NodeType.ACTION, "google_calendar.create", Map.of("brand", "google")),
            node(NodeType.CONDITION, "branch.if", Map.of("brand", "filter")),
            node(NodeType.TRANSFORM, "transform.map", Map.of()),
            node(NodeType.APPROVAL, "approval.request", Map.of("brand", "webhook")))))
            .isEmpty();
    }

    @Test
    void 비AI_노드의_config_tools는_무시한다() {
        assertThat(WorkflowServiceType.extract(List.of(
            node(NodeType.ACTION, "notion.create_page", Map.of("tools", List.of(Map.of("name", "slack")))))))
            .containsExactly(NOTION);
    }

    @Test
    void ieum_agent_구글_도구명을_매핑한다() {
        assertThat(WorkflowServiceType.extract(List.of(
            node(NodeType.AI, "ai.agent", Map.of("tools", List.of("gmail", "builtin:google_sheets_write", "builtin:google_drive_upload"))))))
            .containsExactly(GMAIL, GOOGLE_SHEETS, GOOGLE_DRIVE);
    }

    @Test
    void slack_bot_ACTION과_AI_봇도구는_SLACK이다() {
        assertThat(WorkflowServiceType.extract(List.of(
            node(NodeType.ACTION, "slack_bot.send_channel_message", Map.of()))))
            .containsExactly(SLACK);
        assertThat(WorkflowServiceType.extract(List.of(
            node(NodeType.AI, "ai.agent", Map.of("tools", List.of(Map.of("name", "builtin:slack_send_channel_message")))))))
            .containsExactly(SLACK);
    }

    @Test
    void discord_bot_ACTION과_AI_봇도구는_DISCORD다() {
        assertThat(WorkflowServiceType.extract(List.of(
            node(NodeType.ACTION, "discord_bot.send_channel_message", Map.of()))))
            .containsExactly(DISCORD);
        assertThat(WorkflowServiceType.extract(List.of(
            node(NodeType.AI, "ai.agent", Map.of("tools", List.of(Map.of("name", "builtin:discord_send_channel_message")))))))
            .containsExactly(DISCORD);
    }

    @Test
    void 웹훅_discord와_봇_discord_bot이_함께_있어도_DISCORD_하나() {
        assertThat(WorkflowServiceType.extract(List.of(
            node(NodeType.ACTION, "discord.send_message", Map.of()),
            node(NodeType.ACTION, "discord_bot.send_channel_message", Map.of()))))
            .containsExactly(DISCORD);
    }

    @Test
    void event가_없거나_점이_없으면_아이콘_없음() {
        assertThat(WorkflowServiceType.extract(List.of(
            node(null, null, Map.of("tools", List.of("slack"))),
            node(null, "gmail", Map.of()),
            node(NodeType.AI, "ai.agent", null)))).isEmpty();
    }

    @Test
    void 연동_서비스가_없으면_빈_리스트() {
        assertThat(WorkflowServiceType.extract(
            List.of(node(NodeType.AI, "ai.agent", Map.of("prompt", "hi"))))).isEmpty();
    }
}
