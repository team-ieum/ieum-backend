package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiscordAccessTest {

    @Test
    @DisplayName("toString은 서버 개수만 — 기본 record toString은 봇 토큰 평문을 찍는다(SlackDisconnectService.RevokeTarget 선례)")
    void toStringHidesToken() {
        DiscordAccess access = new DiscordAccess("MTIz.secret-bot-token", List.of("111111111111111111", "222222222222222222"));

        assertThat(access.toString())
            .isEqualTo("DiscordAccess[guildCount=2]")
            .doesNotContain("secret-bot-token")
            .doesNotContain("111111111111111111");
    }

    @Test
    @DisplayName("guildIds는 불변 복사 — 호출자가 원본 목록을 바꿔도 헤더 값이 바뀌지 않는다")
    void guildIdsAreCopied() {
        List<String> source = new ArrayList<>(List.of("111111111111111111"));
        DiscordAccess access = new DiscordAccess("t", source);
        source.add("222222222222222222");

        assertThat(access.guildIds()).containsExactly("111111111111111111");
        assertThatThrownBy(() -> access.guildIds().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Stub은 실제 구현과 같은 예외 — workflow-core 단독 기동에서 Discord 노드는 미연결로 실패")
    void stubThrowsNotConnected() {
        assertThatThrownBy(() -> new StubDiscordAccessProvider().get(UUID.randomUUID()))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.DISCORD_NOT_CONNECTED);
    }
}
