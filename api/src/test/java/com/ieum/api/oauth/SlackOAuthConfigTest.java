package com.ieum.api.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

public class SlackOAuthConfigTest {

    /** spec D3 — scope를 더하면 기존 설치 전원이 재설치해야 하므로 바뀌면 이 테스트가 먼저 깨져야 한다 */
    public static final String D3 = "chat:write,chat:write.public,channels:read,groups:read,im:read,mpim:read,"
        + "channels:history,groups:history,channels:manage,groups:write,im:write,reactions:read,reactions:write,"
        + "users:read,users:read.email,files:write,canvases:write,emoji:read";

    private static Properties yaml(String path) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource(path));
        return factory.getObject();
    }

    @Test
    @DisplayName("운영 yml — 연결 설정은 env 자리표시자(기본값 없음), bot-scopes는 D3 상수. 테스트 yml도 같은 scope")
    void slackOauthSettings() {
        Properties main = yaml("src/main/resources/application.yml");

        assertThat(main)
            .containsEntry("slack.oauth.client-id", "${SLACK_CLIENT_ID}")
            .containsEntry("slack.oauth.client-secret", "${SLACK_CLIENT_SECRET}")
            .containsEntry("slack.oauth.redirect-uri", "${SLACK_REDIRECT_URI}")
            .containsEntry("slack.oauth.frontend-redirect-uri", "${SLACK_FRONTEND_REDIRECT_URI}")
            .containsEntry("slack.oauth.bot-scopes", D3);
        assertThat(yaml("src/test/resources/application.yml")).containsEntry("slack.oauth.bot-scopes", D3);
    }
}
