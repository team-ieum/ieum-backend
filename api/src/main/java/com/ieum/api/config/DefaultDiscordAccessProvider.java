package com.ieum.api.config;

import com.ieum.auth.domain.DiscordGuildLink;
import com.ieum.auth.repository.DiscordGuildLinkRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.engine.executor.DiscordAccess;
import com.ieum.workflowcore.engine.executor.DiscordAccessProvider;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** workflow-core 포트 DiscordAccessProvider → env 봇 토큰 + 사용자 링크. 이 빈이 있으면 StubDiscordAccessProvider는 꺼진다. */
@Component
public class DefaultDiscordAccessProvider implements DiscordAccessProvider {

    private final DiscordGuildLinkRepository linkRepository;
    private final String botToken;

    public DefaultDiscordAccessProvider(DiscordGuildLinkRepository linkRepository,
                                        @Value("${discord.bot-token}") String botToken) {
        this.linkRepository = linkRepository;
        this.botToken = botToken;
    }

    @Override
    public DiscordAccess get(UUID userId) {
        List<String> guildIds = linkRepository.findAllByUserIdOrderByCreatedAtAsc(userId).stream()
            .map(DiscordGuildLink::getGuildId)
            .sorted()
            .toList();
        if (guildIds.isEmpty()) {
            throw new CustomException(ErrorCode.DISCORD_NOT_CONNECTED);
        }
        return new DiscordAccess(botToken, guildIds);
    }
}
