package com.ieum.workflowcore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.domain.enums.TriggerType;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppTriggerSubscriptionTest {

    private final LocalDateTime t0 = LocalDateTime.of(2026, 10, 11, 9, 0, 0);
    private final long t0Sec = t0.atZone(ZoneId.systemDefault()).toEpochSecond();

    private AppTriggerSubscription gmail(String query) {
        return AppTriggerSubscription.create(UUID.randomUUID(), "t1", UUID.randomUUID(),
            TriggerType.GMAIL_NEW_EMAIL, null, query == null ? Map.of() : Map.of("query", query), t0);
    }

    @Test
    @DisplayName("Gmail 신규 행 — 커서는 생성 시각(초), 즉시 폴링 대상")
    void gmailStartsAtNow() {
        AppTriggerSubscription s = gmail("from:x");
        assertThat(s.getCursor()).containsExactly(java.util.Map.entry("afterSec", t0Sec));
        assertThat(s.getNextPollAt()).isEqualTo(t0);
    }

    @Test
    @DisplayName("GitHub 신규 행 — 커서·폴링 없음(webhook 수신)")
    void githubHasNoPolling() {
        AppTriggerSubscription s = AppTriggerSubscription.create(UUID.randomUUID(), "t1", UUID.randomUUID(),
            TriggerType.GITHUB_NEW_ISSUE, "123", Map.of("repoId", 123L, "repo", "o/r"), t0);
        assertThat(s.getCursor()).isNull();
        assertThat(s.getNextPollAt()).isNull();
        assertThat(s.getResourceKey()).isEqualTo("123");
    }

    @Test
    @DisplayName("같은 query로 재구성 — 커서·다음 폴링 시각 유지")
    void sameQueryKeepsPolling() {
        AppTriggerSubscription s = gmail("from:x");
        s.recordPoll(Map.of("afterSec", t0Sec + 100, "seenIds", List.of("m1")), "ERR", t0, t0.plusMinutes(5));

        s.reconfigure(TriggerType.GMAIL_NEW_EMAIL, s.getUserId(), null, Map.of("query", "from:x"), t0.plusHours(1));

        assertThat(s.getCursor()).containsEntry("afterSec", t0Sec + 100);
        assertThat(s.getNextPollAt()).isEqualTo(t0.plusMinutes(5));
        assertThat(s.getLastError()).isEqualTo("ERR");
    }

    @Test
    @DisplayName("query 변경 — 커서를 재구성 시각으로 리셋, 즉시 폴링, 오류 초기화")
    void changedQueryResets() {
        AppTriggerSubscription s = gmail("from:x");
        s.recordPoll(Map.of("afterSec", t0Sec + 100, "seenIds", List.of("m1")), "ERR", t0, t0.plusMinutes(5));
        LocalDateTime t1 = t0.plusHours(1);

        s.reconfigure(TriggerType.GMAIL_NEW_EMAIL, s.getUserId(), null, Map.of("query", "from:y"), t1);

        assertThat(s.getCursor()).containsExactly(java.util.Map.entry("afterSec", t0Sec + 3600));
        assertThat(s.getNextPollAt()).isEqualTo(t1);
        assertThat(s.getLastError()).isNull();
    }

    @Test
    @DisplayName("빈 query → 값 있는 query도 변경이다")
    void emptyToQueryIsChange() {
        AppTriggerSubscription s = gmail(null);
        s.recordPoll(Map.of("afterSec", t0Sec + 100, "seenIds", List.of()), null, t0, t0.plusMinutes(5));

        s.reconfigure(TriggerType.GMAIL_NEW_EMAIL, s.getUserId(), null, Map.of("query", "label:work"), t0.plusHours(1));

        assertThat(s.getCursor()).containsEntry("afterSec", t0Sec + 3600);
    }
}
