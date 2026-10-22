package com.ieum.api.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.auth.domain.DiscordGuildLink;
import com.ieum.auth.repository.DiscordGuildLinkRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** 엔티티 @Table(uniqueConstraints·indexes)가 create-drop 스키마에 실제로 생기는지 본다(운영 update와 같은 엔티티 정의). */
@SpringBootTest
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class DiscordRepositoriesTest {

    private static final String G1 = "111111111111111111";
    private static final String G2 = "222222222222222222";

    @Autowired private DiscordGuildLinkRepository linkRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clean() {
        linkRepository.deleteAll();
    }

    @Test
    @DisplayName("(user_id, guild_id)는 유일 — 같은 사용자가 같은 서버를 두 번 링크하지 못한다")
    void userGuildIsUnique() {
        UUID userId = UUID.randomUUID();
        linkRepository.saveAndFlush(new DiscordGuildLink(userId, G1, "서버", "U1"));

        assertThatThrownBy(() -> linkRepository.saveAndFlush(new DiscordGuildLink(userId, G1, "서버", "U1")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 서버를 두 사용자가, 한 사용자가 서버 여러 개를 링크할 수 있다(D1·D2)")
    void sameGuildManyUsersAndManyGuildsPerUser() {
        UUID me = UUID.randomUUID();
        linkRepository.saveAndFlush(new DiscordGuildLink(me, G1, "서버1", "U1"));
        linkRepository.saveAndFlush(new DiscordGuildLink(me, G2, "서버2", "U1"));
        linkRepository.saveAndFlush(new DiscordGuildLink(UUID.randomUUID(), G1, "서버1", "U2"));

        assertThat(linkRepository.countByGuildId(G1)).isEqualTo(2);
        assertThat(linkRepository.findAllByUserIdOrderByCreatedAtAsc(me))
            .extracting(DiscordGuildLink::getGuildId).containsExactly(G1, G2);
        assertThat(linkRepository.findByUserIdAndGuildId(me, G2)).get()
            .extracting(DiscordGuildLink::getGuildName).isEqualTo("서버2");
        assertThat(linkRepository.existsByUserIdAndGuildId(me, G1)).isTrue();
        assertThat(linkRepository.existsByUserIdAndGuildId(me, "333333333333333333")).isFalse();
    }

    @Test
    @DisplayName("refresh는 서버 이름·Discord 사용자 ID만 바꾼다")
    void refreshUpdatesNameAndDiscordUser() {
        DiscordGuildLink link = linkRepository.saveAndFlush(new DiscordGuildLink(UUID.randomUUID(), G1, "옛 이름", null));

        link.refresh("새 이름", "U9");
        linkRepository.saveAndFlush(link);

        assertThat(linkRepository.findById(link.getId())).get().satisfies(saved -> {
            assertThat(saved.getGuildName()).isEqualTo("새 이름");
            assertThat(saved.getDiscordUserId()).isEqualTo("U9");
            assertThat(saved.getGuildId()).isEqualTo(G1);
        });
    }

    @Test
    @DisplayName("guild_id 인덱스(idx_discord_guild_links_guild)가 엔티티 선언으로 생긴다 — 해제의 남은 링크 수 조회용")
    void guildIndexExists() {
        assertThat(jdbcTemplate.queryForList(
                "select upper(index_name) from information_schema.indexes where upper(table_name) = 'DISCORD_GUILD_LINKS'",
                String.class))
            .contains("IDX_DISCORD_GUILD_LINKS_GUILD");
    }
}
