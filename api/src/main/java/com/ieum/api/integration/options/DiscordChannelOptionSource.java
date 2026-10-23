package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.auth.domain.DiscordGuildLink;
import com.ieum.auth.repository.DiscordGuildLinkRepository;
import com.ieum.auth.service.DiscordApiClient;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * 고른 Discord 서버의 텍스트·공지 채널(spec §10) — 값은 채널 ID, 이름은 {@code #이름}, 카테고리·위치 순(Discord 화면 순서), 한 번에 전부.
 * 내 링크에 있는 서버만 — 봇은 IEUM 전원이 설치한 서버에 들어가 있어 검사 없으면 남의 서버 채널이 보인다.
 * 2026-11-16 전까지는 봇이 볼 수 없는 채널도 나온다(Discord changelog) — 권한 계산은 하지 않고 실행 때 50001 안내에 맡긴다.
 * 트랜잭션을 걸지 않는다(options 관례). 캐시 없음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiscordChannelOptionSource implements OptionSource {

    static final String BOT_MISSING_MESSAGE = "봇이 서버에 없어요. Discord를 다시 연결해 주세요";
    /** 0 = GUILD_TEXT, 5 = GUILD_ANNOUNCEMENT — 첫 노드가 보낼 수 있는 채널 */
    private static final Set<Integer> SENDABLE_TYPES = Set.of(0, 5);
    private static final int CATEGORY_TYPE = 4;

    private final DiscordGuildLinkRepository linkRepository;
    private final DiscordApiClient discordApiClient;

    @Override
    public String key() {
        return "discord.channels";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        String guildId = inputs.get("guild_id") == null ? "" : inputs.get("guild_id").strip();
        if (guildId.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "서버를 먼저 골라 주세요.");
        }
        if (guildId.contains("{{")) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "변수 참조로 지정된 서버는 실행 전이라 채널 목록을 불러올 수 없어요.");
        }
        String linkedGuildId = linkRepository.findByUserIdAndGuildId(userId, guildId)
            .map(DiscordGuildLink::getGuildId)
            .orElseThrow(() -> new CustomException(ErrorCode.DISCORD_GUILD_NOT_CONNECTED));
        String q = normalize(inputs.get("q"));
        JsonNode all = channelsOf(linkedGuildId);
        Map<String, Integer> categoryPosition = new HashMap<>();
        for (JsonNode channel : all) {
            if (channel.path("type").asInt(-1) == CATEGORY_TYPE) {
                categoryPosition.put(channel.path("id").asText(), channel.path("position").asInt(0));
            }
        }
        List<JsonNode> sendable = new ArrayList<>();
        for (JsonNode channel : all) {
            if (SENDABLE_TYPES.contains(channel.path("type").asInt(-1))
                    && (q == null || channel.path("name").asText("").toLowerCase(Locale.ROOT).contains(q))) {
                sendable.add(channel);
            }
        }
        List<OptionItem> items = sendable.stream()
            // Discord 화면 순서 — position은 카테고리 안에서만 의미가 있다. 카테고리 없는 채널이 맨 앞(-1).
            // position이 같은 카테고리끼리 채널이 섞이지 않게 카테고리 id로 한 번 더 묶는다
            .sorted(Comparator.<JsonNode>comparingInt(
                    channel -> categoryPosition.getOrDefault(channel.path("parent_id").asText(""), -1))
                .thenComparingLong(channel -> channel.path("parent_id").asLong(0))
                .thenComparingInt(channel -> channel.path("position").asInt(0))
                .thenComparingLong(channel -> channel.path("id").asLong(0)))
            .map(channel -> new OptionItem(channel.path("id").asText(), "#" + channel.path("name").asText("")))
            .toList();
        return new OptionPage(items, null);
    }

    /** 공백뿐이면 없음. 라벨이 {@code #이름}이라 앞의 #은 떼고, 소문자로 비교한다(SlackChannelOptionSource와 같은 규칙) */
    private static String normalize(String q) {
        if (q == null) {
            return null;
        }
        String stripped = q.strip();
        if (stripped.startsWith("#")) {
            stripped = stripped.substring(1).strip();
        }
        return stripped.isEmpty() ? null : stripped.toLowerCase(Locale.ROOT);
    }

    private JsonNode channelsOf(String guildId) {
        try {
            return discordApiClient.guildChannels(guildId);
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 404 || status == 403) {
                throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED, BOT_MISSING_MESSAGE); // 10004 · 50001
            }
            if (status == 401) {
                log.error("[DiscordChannelOptionSource] 봇 토큰 거절(401) — DISCORD_BOT_TOKEN 확인 필요");
                throw new CustomException(ErrorCode.DISCORD_API_UNAVAILABLE);
            }
            if (status == 429) {
                log.warn("[DiscordChannelOptionSource] 채널 목록 429 — guild: {}", guildId);
                throw new CustomException(ErrorCode.DISCORD_API_UNAVAILABLE);
            }
            throw new CustomException(ErrorCode.INVALID_INPUT, "Discord가 요청을 거부했어요 (HTTP " + status + ")");
        } catch (RestClientException e) {
            // 5xx·타임아웃·연결 실패 — 사용자가 고칠 수 없는 일시 장애
            log.warn("[DiscordChannelOptionSource] 채널 목록 실패 — guild: {}, {}", guildId, DiscordApiClient.describe(e));
            throw new CustomException(ErrorCode.DISCORD_API_UNAVAILABLE);
        }
    }
}
