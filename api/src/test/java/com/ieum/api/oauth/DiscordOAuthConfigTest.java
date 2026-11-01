package com.ieum.api.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

public class DiscordOAuthConfigTest {

    /** spec D6 — 권한을 늘리면 서버마다 재설치. 십진 문자열로 고정(int 시프트 1 << 38은 64가 된다) */
    public static final String D6 = "275146460176";

    private static Properties yaml(String path) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource(path));
        return factory.getObject();
    }

    @Test
    @DisplayName("D6 권한 합 = VIEW_CHANNEL·SEND_MESSAGES·SEND_MESSAGES_IN_THREADS·EMBED_LINKS·ATTACH_FILES·READ_MESSAGE_HISTORY·MANAGE_ROLES·MANAGE_CHANNELS (long 시프트)")
    void permissionsSum() {
        long sum = (1L << 10) | (1L << 11) | (1L << 38) | (1L << 14) | (1L << 15) | (1L << 16) | (1L << 28) | (1L << 4);

        assertThat(Long.toString(sum)).isEqualTo(D6);
    }

    @Test
    @DisplayName("운영 yml — 연결·봇 토큰은 env 자리표시자(기본값 없음), permissions는 D6 문자열. 테스트 yml도 같은 권한")
    void discordSettings() {
        Properties main = yaml("src/main/resources/application.yml");

        assertThat(main)
            .containsEntry("discord.bot-token", "${DISCORD_BOT_TOKEN}")
            .containsEntry("discord.oauth.client-id", "${DISCORD_CLIENT_ID}")
            .containsEntry("discord.oauth.client-secret", "${DISCORD_CLIENT_SECRET}")
            .containsEntry("discord.oauth.redirect-uri", "${DISCORD_REDIRECT_URI}")
            .containsEntry("discord.oauth.frontend-redirect-uri", "${DISCORD_FRONTEND_REDIRECT_URI}")
            .containsEntry("discord.oauth.permissions", D6);
        assertThat(yaml("src/test/resources/application.yml")).containsEntry("discord.oauth.permissions", D6);
    }
}
