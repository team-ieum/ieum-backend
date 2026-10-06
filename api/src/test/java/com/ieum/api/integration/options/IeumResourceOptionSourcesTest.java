package com.ieum.api.integration.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.api.mcp.domain.McpServerCatalog;
import com.ieum.api.mcp.repository.McpServerCatalogRepository;
import com.ieum.api.webhookcredential.domain.WebhookCredential;
import com.ieum.api.webhookcredential.domain.WebhookProvider;
import com.ieum.api.webhookcredential.repository.WebhookCredentialRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 웹훅 3키·MCP 공급원의 provider 필터·비활성 제외·사용자 범위를 고정한다 (IEUM-BE-76). */
class IeumResourceOptionSourcesTest {

    private final WebhookCredentialRepository webhookRepository = mock(WebhookCredentialRepository.class);
    private final McpServerCatalogRepository mcpRepository = mock(McpServerCatalogRepository.class);
    private final WebhookOptionSourceConfig config = new WebhookOptionSourceConfig();
    private final OptionSource allWebhooks = config.ieumWebhooksOptionSource(webhookRepository);
    private final OptionSource slackWebhooks = config.slackWebhooksOptionSource(webhookRepository);
    private final OptionSource discordWebhooks = config.discordWebhooksOptionSource(webhookRepository);
    private final McpServersOptionSource mcpServers = new McpServersOptionSource(mcpRepository);
    private final UUID userId = UUID.randomUUID();

    private static WebhookCredential webhook(UUID owner, WebhookProvider provider, String name, boolean enabled) {
        WebhookCredential w = WebhookCredential.builder()
            .userId(owner)
            .provider(provider)
            .displayName(name)
            .encryptedWebhookUrl("enc-url")
            .enabled(enabled)
            .build();
        ReflectionTestUtils.setField(w, "id", UUID.randomUUID());
        return w;
    }

    private static McpServerCatalog mcp(UUID owner, String name, boolean enabled) {
        McpServerCatalog c = McpServerCatalog.builder()
            .userId(owner)
            .displayName(name)
            .serverUrl("https://mcp.example.com")
            .enabled(enabled)
            .build();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        return c;
    }

    private static OptionItem item(WebhookCredential w, String name) {
        return new OptionItem(w.getId().toString(), name);
    }

    @Test
    @DisplayName("키 철자 — agent 카탈로그 optionsSource와 1:1")
    void keys() {
        assertThat(allWebhooks.key()).isEqualTo("ieum.webhooks");
        assertThat(slackWebhooks.key()).isEqualTo("slack.webhooks");
        assertThat(discordWebhooks.key()).isEqualTo("discord.webhooks");
        assertThat(mcpServers.key()).isEqualTo("ieum.mcp_servers");
    }

    @Test
    @DisplayName("slack.webhooks·discord.webhooks — 그 서비스 웹훅만, 이름 그대로, 페이지 없음")
    void serviceKeysFilterProvider() {
        WebhookCredential slack = webhook(userId, WebhookProvider.SLACK, "팀 알림", true);
        WebhookCredential discord = webhook(userId, WebhookProvider.DISCORD, "봇 채널", true);
        given(webhookRepository.findByUserId(userId)).willReturn(List.of(slack, discord));

        OptionPage slackPage = slackWebhooks.fetch(userId, Map.of(), null);
        OptionPage discordPage = discordWebhooks.fetch(userId, Map.of(), null);

        assertThat(slackPage.items()).containsExactly(item(slack, "팀 알림"));
        assertThat(discordPage.items()).containsExactly(item(discord, "봇 채널"));
        assertThat(slackPage.nextCursor()).isNull();
    }

    @Test
    @DisplayName("ieum.webhooks — 서비스 구분 없이 전부, 이름 뒤에 서비스 표시(HTTP 노드용)")
    void allKeyMarksProvider() {
        WebhookCredential slack = webhook(userId, WebhookProvider.SLACK, "팀 알림", true);
        WebhookCredential discord = webhook(userId, WebhookProvider.DISCORD, "봇 채널", true);
        given(webhookRepository.findByUserId(userId)).willReturn(List.of(slack, discord));

        OptionPage page = allWebhooks.fetch(userId, Map.of(), null);

        assertThat(page.items()).containsExactly(item(slack, "팀 알림 (SLACK)"), item(discord, "봇 채널 (DISCORD)"));
    }

    @Test
    @DisplayName("비활성 웹훅은 세 키 모두에서 빠진다 — 실행이 건너뛰어 조용히 실패하므로")
    void disabledExcluded() {
        WebhookCredential off = webhook(userId, WebhookProvider.SLACK, "꺼둔 웹훅", false);
        given(webhookRepository.findByUserId(userId)).willReturn(List.of(off));

        assertThat(allWebhooks.fetch(userId, Map.of(), null).items()).isEmpty();
        assertThat(slackWebhooks.fetch(userId, Map.of(), null).items()).isEmpty();
    }

    @Test
    @DisplayName("웹훅 — 요청자 userId 조회 하나만, 남의 웹훅 미노출")
    void webhooksOnlyCallerScoped() {
        WebhookCredential mine = webhook(userId, WebhookProvider.DISCORD, "내 웹훅", true);
        UUID otherUser = UUID.randomUUID();
        given(webhookRepository.findByUserId(userId)).willReturn(List.of(mine));
        given(webhookRepository.findByUserId(otherUser))
            .willReturn(List.of(webhook(otherUser, WebhookProvider.DISCORD, "남의 웹훅", true)));

        OptionPage page = discordWebhooks.fetch(userId, Map.of(), null);

        assertThat(page.items()).containsExactly(item(mine, "내 웹훅"));
        verify(webhookRepository).findByUserId(userId);
        verifyNoMoreInteractions(webhookRepository);
    }

    @Test
    @DisplayName("ieum.mcp_servers — 요청자의 활성 서버만 {UUID, 표시 이름}, 남의 서버 미노출")
    void mcpOnlyCallerScopedAndEnabled() {
        McpServerCatalog on = mcp(userId, "사내 위키", true);
        McpServerCatalog off = mcp(userId, "꺼둔 서버", false);
        UUID otherUser = UUID.randomUUID();
        given(mcpRepository.findByUserId(userId)).willReturn(List.of(on, off));
        given(mcpRepository.findByUserId(otherUser)).willReturn(List.of(mcp(otherUser, "남의 서버", true)));

        OptionPage page = mcpServers.fetch(userId, Map.of(), null);

        assertThat(page.items()).containsExactly(new OptionItem(on.getId().toString(), "사내 위키"));
        assertThat(page.nextCursor()).isNull();
        verify(mcpRepository).findByUserId(userId);
        verifyNoMoreInteractions(mcpRepository);
    }
}
