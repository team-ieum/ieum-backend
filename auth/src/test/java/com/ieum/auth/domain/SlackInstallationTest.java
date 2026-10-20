package com.ieum.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SlackInstallationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 12, 0);

    private static SlackInstallation expiringAt(LocalDateTime expiresAt) {
        SlackInstallation installation = new SlackInstallation("T1");
        installation.applyInstall("enc-bot", "enc-refresh", expiresAt, "chat:write", "팀", "UBOT", "A1");
        return installation;
    }

    @Test
    @DisplayName("만료 시각이 없으면(rotation 꺼짐) 갱신하지 않는다")
    void noExpiryNeverRefreshes() {
        assertThat(expiringAt(null).needsRefresh(NOW)).isFalse();
    }

    @ParameterizedTest(name = "만료까지 {0}분 → 갱신 {1}")
    @CsvSource({"10, false", "6, false", "5, true", "3, true", "0, true", "-60, true"})
    @DisplayName("5분 넘게 남았을 때만 저장 토큰을 쓴다 — 5분 이하·이미 지남은 갱신")
    void refreshWithinFiveMinutes(long minutesLeft, boolean expected) {
        assertThat(expiringAt(NOW.plusMinutes(minutesLeft)).needsRefresh(NOW)).isEqualTo(expected);
    }

    @Test
    @DisplayName("rotate는 access·refresh·만료를 함께 바꾸고, applyInstall은 설치 응답 전부를 덮어쓴다")
    void rotateAndApplyOverwrite() {
        SlackInstallation installation = expiringAt(NOW);
        installation.rotate("enc-bot-2", "enc-refresh-2", NOW.plusHours(12));

        assertThat(installation.getBotToken()).isEqualTo("enc-bot-2");
        assertThat(installation.getRefreshToken()).isEqualTo("enc-refresh-2");
        assertThat(installation.getTokenExpiresAt()).isEqualTo(NOW.plusHours(12));

        installation.applyInstall("enc-bot-3", null, null, "chat:write,users:read", "새 팀", "UBOT2", "A2");
        assertThat(installation.getRefreshToken()).isNull();
        assertThat(installation.getTokenExpiresAt()).isNull();
        assertThat(installation.getScopes()).isEqualTo("chat:write,users:read");
        assertThat(installation.getTeamName()).isEqualTo("새 팀");
        assertThat(installation.getTeamId()).isEqualTo("T1");
    }
}
