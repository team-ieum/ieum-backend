package com.ieum.api.integration.options;

import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.auth.domain.DiscordGuildLink;
import com.ieum.auth.repository.DiscordGuildLinkRepository;
import com.ieum.auth.service.DiscordApiClient;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * 연결한 Discord 서버(spec §10, D9) — 값은 서버 ID, 이름은 링크마다 GET /guilds/{id}로 받은 실시간 이름.
 * 봇이 쫓겨난 서버는 빼지 않고 "(봇 없음 · 다시 연결)"을 붙인다(google.calendars "(읽기 전용)" 선례). 일시 오류는 연결 시점 이름.
 * DB 이름은 갱신하지 않는다. 트랜잭션을 걸지 않는다(options 관례).
 * ponytail: 사용자당 링크 수가 적어 순차 호출 — 많아지면 전용 executor로.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiscordGuildOptionSource implements OptionSource {

    static final String BOT_MISSING_SUFFIX = " (봇 없음 · 다시 연결)";

    private final DiscordGuildLinkRepository linkRepository;
    private final DiscordApiClient discordApiClient;

    @Override
    public String key() {
        return "discord.guilds";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        List<DiscordGuildLink> links = linkRepository.findAllByUserIdOrderByCreatedAtAsc(userId);
        if (links.isEmpty()) {
            throw new CustomException(ErrorCode.DISCORD_NOT_CONNECTED);
        }
        return new OptionPage(links.stream().map(this::item).toList(), null);
    }

    private OptionItem item(DiscordGuildLink link) {
        String guildId = link.getGuildId();
        String stored = link.getGuildName() != null ? link.getGuildName() : guildId;
        try {
            return new OptionItem(guildId, discordApiClient.guild(guildId).path("name").asText(stored));
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 404 || status == 403) {
                return new OptionItem(guildId, stored + BOT_MISSING_SUFFIX); // 10004 Unknown Guild · 50001 Missing Access
            }
            if (status == 401) {
                log.error("[DiscordGuildOptionSource] 봇 토큰 거절(401) — DISCORD_BOT_TOKEN 확인 필요");
            } else {
                log.warn("[DiscordGuildOptionSource] 서버 조회 실패 — guild: {}, {}", guildId, DiscordApiClient.describe(e));
            }
            return new OptionItem(guildId, stored);
        } catch (RestClientException e) {
            log.warn("[DiscordGuildOptionSource] 서버 조회 실패 — guild: {}, {}", guildId, DiscordApiClient.describe(e));
            return new OptionItem(guildId, stored);
        }
    }
}
