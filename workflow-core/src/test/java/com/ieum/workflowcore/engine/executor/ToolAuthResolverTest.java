package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolAuthResolverTest {

    @Mock
    private CredentialProvider credentialProvider;

    @Mock
    private NotionTokenProvider notionTokenProvider;

    @Mock
    private SlackTokenProvider slackTokenProvider;

    @Mock
    private DiscordAccessProvider discordAccessProvider;

    @InjectMocks
    private ToolAuthResolver toolAuthResolver;

    @Test
    @DisplayName("Notion 도구 사용 시 credentialId가 없으면 OAuth 연동 토큰을 반환한다")
    void resolveHeaders_NotionOAuth_Fallback() {
        // given
        UUID userId = UUID.randomUUID();
        String notionToken = "decrypted-notion-token";

        when(notionTokenProvider.getAccessToken(userId)).thenReturn(Optional.of(notionToken));

        List<Map<String, Object>> tools = List.of(
            Map.of("name", "builtin:notion_create_page")
        );

        // when
        Map<String, String> headers = toolAuthResolver.resolveHeaders(tools, userId);

        // then
        assertThat(headers).containsEntry("X-Notion-Token", notionToken);
    }

    @Test
    @DisplayName("Notion 도구 사용 시 credentialId가 있으면 BYOK 키를 우선 반환한다")
    void resolveHeaders_Notion_BYOK_Priority() {
        // given
        UUID userId = UUID.randomUUID();
        String byokKey = "byok-notion-key";
        String credentialId = "test-cred-id";

        when(credentialProvider.getDecryptedApiKey(credentialId, userId)).thenReturn(byokKey);

        List<Map<String, Object>> tools = List.of(
            Map.of("name", "builtin:notion_create_page", "credentialId", credentialId)
        );

        // when
        Map<String, String> headers = toolAuthResolver.resolveHeaders(tools, userId);

        // then
        assertThat(headers).containsEntry("X-Notion-Token", byokKey);
    }

    @Test
    @DisplayName("Slack 봇 도구 → X-Slack-Token에 봇 토큰. 웹훅 도구 slack은 헤더도 토큰 조회도 없다")
    void slackBotTool_putsBotTokenHeader() {
        UUID userId = UUID.randomUUID();
        when(slackTokenProvider.getValidBotToken(userId)).thenReturn("xoxb-bot");

        Map<String, String> headers = toolAuthResolver.resolveHeaders(List.of(
            Map.of("name", "builtin:slack_send_channel_message"),
            Map.of("name", "slack")), userId);

        assertThat(headers).containsExactly(Map.entry("X-Slack-Token", "xoxb-bot"));
    }

    @Test
    @DisplayName("웹훅 도구 slack만 있으면 헤더도 Slack 토큰 조회도 없다 — 미연결 사용자의 웹훅 실행을 막지 않는다")
    void slackWebhookToolAlone_noHeaderNoLookup() {
        Map<String, String> headers = toolAuthResolver.resolveHeaders(
            List.of(Map.of("name", "slack")), UUID.randomUUID());

        assertThat(headers).isEmpty();
        verifyNoInteractions(slackTokenProvider);
    }

    @Test
    @DisplayName("Slack 봇 도구에 credentialId·tool.auth가 붙어 있어도 무시한다 — LLM 키가 slack.com으로 나가지 않게")
    void slackBotTool_ignoresByokCredential() {
        UUID userId = UUID.randomUUID();
        when(slackTokenProvider.getValidBotToken(userId)).thenReturn("xoxb-bot");

        Map<String, String> headers = toolAuthResolver.resolveHeaders(List.of(
            Map.of("name", "builtin:slack_send_channel_message", "credentialId", "llm-cred-id",
                "auth", Map.of("type", "plain", "value", "sk-llm-key"))), userId);

        assertThat(headers).containsEntry("X-Slack-Token", "xoxb-bot");
        verifyNoInteractions(credentialProvider);
    }

    @Test
    @DisplayName("[Review Focus 2] Slack 봇 도구가 여럿이어도 토큰 조회는 한 번")
    void slackToolsShareOneTokenLookup() {
        UUID userId = UUID.randomUUID();
        when(slackTokenProvider.getValidBotToken(userId)).thenReturn("xoxb-bot");

        toolAuthResolver.resolveHeaders(List.of(
            Map.of("name", "builtin:slack_send_channel_message"),
            Map.of("name", "builtin:slack_add_reaction")), userId);

        verify(slackTokenProvider, times(1)).getValidBotToken(userId);
    }

    @Test
    @DisplayName("Slack 미연결 → SLACK_NOT_CONNECTED가 그대로 올라간다(노드 실패)")
    void slackNotConnected_propagates() {
        UUID userId = UUID.randomUUID();
        when(slackTokenProvider.getValidBotToken(userId)).thenThrow(new CustomException(ErrorCode.SLACK_NOT_CONNECTED));

        assertThatThrownBy(() -> toolAuthResolver.resolveHeaders(
                List.of(Map.of("name", "builtin:slack_send_channel_message")), userId))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.SLACK_NOT_CONNECTED);
    }

    @Test
    @DisplayName("Discord 봇 도구 → X-Discord-Token(봇 토큰)·X-Discord-Guild-Ids(쉼표). 웹훅 도구 discord는 헤더·조회 없음")
    void discordBotTool_putsTokenAndGuildIds() {
        UUID userId = UUID.randomUUID();
        when(discordAccessProvider.get(userId))
            .thenReturn(new DiscordAccess("bot-token", List.of("111111111111111111", "222222222222222222")));

        Map<String, String> headers = toolAuthResolver.resolveHeaders(List.of(
            Map.of("name", "builtin:discord_send_channel_message"),
            Map.of("name", "discord")), userId);

        assertThat(headers).containsExactly(
            Map.entry("X-Discord-Token", "bot-token"),
            Map.entry("X-Discord-Guild-Ids", "111111111111111111,222222222222222222"));
    }

    @Test
    @DisplayName("웹훅 도구 discord만 있으면 헤더도 Discord 조회도 없다 — 미연결 사용자의 웹훅 실행을 막지 않는다")
    void discordWebhookToolAlone_noHeaderNoLookup() {
        Map<String, String> headers = toolAuthResolver.resolveHeaders(
            List.of(Map.of("name", "discord")), UUID.randomUUID());

        assertThat(headers).isEmpty();
        verifyNoInteractions(discordAccessProvider);
    }

    @Test
    @DisplayName("Discord 봇 도구에 credentialId·tool.auth가 붙어 있어도 무시한다 — LLM 키가 discord.com으로 나가지 않게")
    void discordBotTool_ignoresByokCredential() {
        UUID userId = UUID.randomUUID();
        when(discordAccessProvider.get(userId)).thenReturn(new DiscordAccess("bot-token", List.of("111111111111111111")));

        Map<String, String> headers = toolAuthResolver.resolveHeaders(List.of(
            Map.of("name", "builtin:discord_send_channel_message", "credentialId", "llm-cred-id",
                "auth", Map.of("type", "plain", "value", "sk-llm-key"))), userId);

        assertThat(headers).containsEntry("X-Discord-Token", "bot-token").doesNotContainValue("sk-llm-key");
        verifyNoInteractions(credentialProvider);
    }

    @Test
    @DisplayName("[Review Focus 2] Discord 봇 도구가 여럿이어도 조회는 한 번")
    void discordToolsShareOneLookup() {
        UUID userId = UUID.randomUUID();
        when(discordAccessProvider.get(userId)).thenReturn(new DiscordAccess("bot-token", List.of("111111111111111111")));

        toolAuthResolver.resolveHeaders(List.of(
            Map.of("name", "builtin:discord_send_channel_message"),
            Map.of("name", "builtin:discord_create_thread")), userId);

        verify(discordAccessProvider, times(1)).get(userId);
    }

    @Test
    @DisplayName("Slack·Discord 봇 도구가 함께 있으면 두 앱 헤더가 모두 실린다")
    void slackAndDiscordTogether() {
        UUID userId = UUID.randomUUID();
        when(slackTokenProvider.getValidBotToken(userId)).thenReturn("xoxb-bot");
        when(discordAccessProvider.get(userId)).thenReturn(new DiscordAccess("bot-token", List.of("111111111111111111")));

        Map<String, String> headers = toolAuthResolver.resolveHeaders(List.of(
            Map.of("name", "builtin:slack_send_channel_message"),
            Map.of("name", "builtin:discord_send_channel_message")), userId);

        assertThat(headers).containsOnlyKeys("X-Slack-Token", "X-Discord-Token", "X-Discord-Guild-Ids");
    }

    @Test
    @DisplayName("Discord 미연결 → DISCORD_NOT_CONNECTED가 그대로 올라간다(노드 실패)")
    void discordNotConnected_propagates() {
        UUID userId = UUID.randomUUID();
        when(discordAccessProvider.get(userId)).thenThrow(new CustomException(ErrorCode.DISCORD_NOT_CONNECTED));

        assertThatThrownBy(() -> toolAuthResolver.resolveHeaders(
                List.of(Map.of("name", "builtin:discord_send_channel_message")), userId))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.DISCORD_NOT_CONNECTED);
    }
}
