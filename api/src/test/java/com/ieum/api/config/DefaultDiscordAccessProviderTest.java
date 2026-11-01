package com.ieum.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ieum.auth.domain.DiscordGuildLink;
import com.ieum.auth.repository.DiscordGuildLinkRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.engine.executor.DiscordAccess;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultDiscordAccessProviderTest {

    private final DiscordGuildLinkRepository linkRepository = mock(DiscordGuildLinkRepository.class);
    private final DefaultDiscordAccessProvider provider = new DefaultDiscordAccessProvider(linkRepository, "env-bot-token");
    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("env 봇 토큰 + 내 링크 guild_id를 정렬해 준다(연결 순서와 무관)")
    void tokenAndSortedGuildIds() {
        given(linkRepository.findAllByUserIdOrderByCreatedAtAsc(userId)).willReturn(List.of(
            new DiscordGuildLink(userId, "333333333333333333", "c", null),
            new DiscordGuildLink(userId, "111111111111111111", "a", null)));

        DiscordAccess access = provider.get(userId);

        assertThat(access.botToken()).isEqualTo("env-bot-token");
        assertThat(access.guildIds()).containsExactly("111111111111111111", "333333333333333333");
    }

    @Test
    @DisplayName("링크가 없으면 DISCORD_NOT_CONNECTED — 헤더 없이 agent까지 가서 애매하게 실패하지 않게")
    void noLinksThrows() {
        given(linkRepository.findAllByUserIdOrderByCreatedAtAsc(userId)).willReturn(List.of());

        assertThatThrownBy(() -> provider.get(userId))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.DISCORD_NOT_CONNECTED);
    }
}
