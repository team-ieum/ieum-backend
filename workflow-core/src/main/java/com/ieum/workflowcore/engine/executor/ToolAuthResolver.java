package com.ieum.workflowcore.engine.executor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolAuthResolver {

    private static final Map<String, String> HEADER_BY_TOOL_PREFIX = Map.of(
        ToolAuthRule.NOTION_BUILTIN_PREFIX, "X-Notion-Token",
        ToolAuthRule.GITHUB_BUILTIN_PREFIX, "X-GitHub-Token"
    );
    private static final String SLACK_HEADER = "X-Slack-Token";
    private static final String DISCORD_TOKEN_HEADER = "X-Discord-Token";
    private static final String DISCORD_GUILD_IDS_HEADER = "X-Discord-Guild-Ids";

    private final CredentialProvider credentialProvider;
    private final NotionTokenProvider notionTokenProvider;
    private final GitHubTokenProvider gitHubTokenProvider;
    private final SlackTokenProvider slackTokenProvider;
    private final DiscordAccessProvider discordAccessProvider;

    public Map<String, String> resolveHeaders(List<Map<String, Object>> tools, UUID userId) {
        if (tools == null || tools.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (Map<String, Object> tool : tools) {
            String toolName = getString(tool, "name").orElse(null);
            if (toolName == null) {
                continue;
            }
            // Slack 봇 도구는 BYOK 분기(tool.auth·credentialId)를 타지 않는다 — 잘못 붙은 credentialId면 사용자 LLM 키가
            // X-Slack-Token으로 slack.com까지 나간다(spec §7). 그래서 HEADER_BY_TOOL_PREFIX에 넣지 않고 여기서 먼저 끊는다.
            // 미연결이면 SLACK_NOT_CONNECTED가 그대로 올라가 노드 실패가 된다. 도구가 여럿이어도 토큰 조회는 한 번.
            if (toolName.startsWith(ToolAuthRule.SLACK_BUILTIN_PREFIX)) {
                headers.computeIfAbsent(SLACK_HEADER, header -> slackTokenProvider.getValidBotToken(userId));
                continue;
            }
            // Discord 봇 도구도 같은 이유로 BYOK 분기 앞에서 끊는다(spec §7) — 봇 토큰과 허용 서버 목록을 한 번만 싣는다.
            // 미연결이면 DISCORD_NOT_CONNECTED가 그대로 올라가 노드 실패가 된다.
            if (toolName.startsWith(ToolAuthRule.DISCORD_BUILTIN_PREFIX)) {
                if (!headers.containsKey(DISCORD_TOKEN_HEADER)) {
                    DiscordAccess access = discordAccessProvider.get(userId);
                    headers.put(DISCORD_TOKEN_HEADER, access.botToken());
                    headers.put(DISCORD_GUILD_IDS_HEADER, String.join(",", access.guildIds()));
                }
                continue;
            }

            String headerName = resolveHeaderName(toolName).orElse(null);
            if (headerName == null) {
                continue;
            }

            resolveAuthValue(tool, toolName, userId)
                .ifPresentOrElse(
                    value -> headers.put(headerName, value),
                    () -> log.warn("[ToolAuthResolver] 빌트인 도구 사용이지만 auth 설정이 없음 — toolName: {}", toolName)
                );
        }

        return headers;
    }

    private Optional<String> resolveHeaderName(String toolName) {
        return HEADER_BY_TOOL_PREFIX.entrySet().stream()
            .filter(entry -> toolName.startsWith(entry.getKey()))
            .map(Map.Entry::getValue)
            .findFirst();
    }

    private Optional<String> resolveAuthValue(Map<String, Object> tool, String toolName, UUID userId) {
        Object auth = tool.get("auth");
        if (auth instanceof Map<?, ?> authMap) {
            return resolveStructuredAuth(authMap, toolName, userId);
        }

        return resolveCredential(getString(tool, "credentialId"), toolName, userId);
    }

    private Optional<String> resolveStructuredAuth(Map<?, ?> auth, String toolName, UUID userId) {
        Optional<String> credentialId = getString(auth, "credentialId");
        String type = getString(auth, "type")
            .orElseGet(() -> credentialId.isPresent() ? "credential" : null);

        if (type == null) {
            return Optional.empty();
        }

        return switch (type.toLowerCase(Locale.ROOT)) {
            case "credential" -> resolveCredential(credentialId, toolName, userId);
            case "secret", "plain" -> getString(auth, "value");
            default -> {
                log.warn("[ToolAuthResolver] 지원하지 않는 도구 auth type — toolName: {}, type: {}", toolName, type);
                yield Optional.empty();
            }
        };
    }

    private Optional<String> resolveCredential(Optional<String> credentialId, String toolName, UUID userId) {
        // 1순위: credentialId BYOK 방식 (기존 동작 유지)
        if (credentialId.isPresent() && !credentialId.get().isBlank()) {
            return credentialId.map(id -> {
                log.debug("[ToolAuthResolver] 도구 인증 credential 조회 — toolName: {}", toolName);
                return credentialProvider.getDecryptedApiKey(id, userId);
            });
        }
        // 2순위: OAuth connected_accounts 방식 (Notion 도구인 경우)
        if (userId != null && toolName.startsWith(ToolAuthRule.NOTION_BUILTIN_PREFIX)) {
            Optional<String> notionToken = notionTokenProvider.getAccessToken(userId);
            if (notionToken.isEmpty()) {
                log.warn("[ToolAuthResolver] Notion OAuth 연동 없음 — userId: {}, toolName: {}", userId, toolName);
            }
            return notionToken;
        }
        if (userId != null && toolName.startsWith(ToolAuthRule.GITHUB_BUILTIN_PREFIX)) {
            Optional<String> githubToken = gitHubTokenProvider.getAccessToken(userId);
            if (githubToken.isEmpty()) {
                log.warn("[ToolAuthResolver] GitHub not connected — userId: {}, tool: {}",
                    userId, toolName);
            }
            return githubToken;
        }
        return Optional.empty();
    }

    private Optional<String> getString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return Optional.of(stringValue);
        }
        return Optional.empty();
    }
}
