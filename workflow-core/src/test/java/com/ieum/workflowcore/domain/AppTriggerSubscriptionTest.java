package com.ieum.workflowcore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.trigger.PollStart;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppTriggerSubscriptionTest {

    private final LocalDateTime t0 = LocalDateTime.of(2026, 10, 11, 9, 0, 0);
    private final PollStart start0 = new PollStart(Map.of("afterSec", 100L), t0);
    private final PollStart start1 = new PollStart(Map.of("afterSec", 200L), t0.plusHours(1));

    private AppTriggerSubscription polling(Map<String, Object> config) {
        return AppTriggerSubscription.create(UUID.randomUUID(), "t1", UUID.randomUUID(),
            TriggerType.GMAIL_NEW_EMAIL, null, config, start0);
    }

    @Test
    @DisplayName("신규 행 — 커서·다음 폴링 시각은 받은 PollStart 그대로")
    void createUsesPollStart() {
        AppTriggerSubscription s = polling(Map.of("query", "from:x"));

        assertThat(s.getCursor()).isEqualTo(Map.of("afterSec", 100L));
        assertThat(s.getNextPollAt()).isEqualTo(t0);
        assertThat(s.getLastError()).isNull();
    }

    @Test
    @DisplayName("PollStart.NONE — 커서·폴링 없음(webhook 수신형)")
    void noneHasNoPolling() {
        AppTriggerSubscription s = AppTriggerSubscription.create(UUID.randomUUID(), "t1", UUID.randomUUID(),
            TriggerType.GITHUB_NEW_ISSUE, "123", Map.of("repoId", 123L), PollStart.NONE);

        assertThat(s.getCursor()).isNull();
        assertThat(s.getNextPollAt()).isNull();
        assertThat(s.getResourceKey()).isEqualTo("123");
    }

    @Test
    @DisplayName("[Review Focus] 같은 스냅샷(Map 구현이 달라도)으로 재구성 — 커서·다음 폴링 시각·오류 유지")
    void sameConfigKeepsPolling() {
        AppTriggerSubscription s = polling(Map.of("query", "from:x"));
        s.recordPoll(Map.of("afterSec", 150L, "seen", Map.of("m1", 150)), "ERR", t0, t0.plusMinutes(5));

        s.reconfigure(TriggerType.GMAIL_NEW_EMAIL, s.getUserId(), null,
            new HashMap<>(Map.of("query", "from:x")), start1);

        assertThat(s.getCursor()).containsEntry("afterSec", 150L);
        assertThat(s.getNextPollAt()).isEqualTo(t0.plusMinutes(5));
        assertThat(s.getLastError()).isEqualTo("ERR");
    }

    @Test
    @DisplayName("[Review Focus] 스냅샷 변경 — 새 PollStart로 리셋, 오류 초기화")
    void changedConfigResets() {
        AppTriggerSubscription s = polling(Map.of("query", "from:x"));
        s.recordPoll(Map.of("afterSec", 150L, "seen", Map.of("m1", 150)), "ERR", t0, t0.plusMinutes(5));

        s.reconfigure(TriggerType.GMAIL_NEW_EMAIL, s.getUserId(), null, Map.of("query", "from:y"), start1);

        assertThat(s.getCursor()).isEqualTo(Map.of("afterSec", 200L));
        assertThat(s.getNextPollAt()).isEqualTo(t0.plusHours(1));
        assertThat(s.getLastError()).isNull();
        assertThat(s.getConfig()).isEqualTo(Map.of("query", "from:y"));
    }

    @Test
    @DisplayName("빈 스냅샷 → 값 있는 스냅샷도 변경이다")
    void emptyToQueryIsChange() {
        AppTriggerSubscription s = polling(Map.of());
        s.recordPoll(Map.of("afterSec", 150L, "seen", List.of()), null, t0, t0.plusMinutes(5));

        s.reconfigure(TriggerType.GMAIL_NEW_EMAIL, s.getUserId(), null, Map.of("query", "label:work"), start1);

        assertThat(s.getCursor()).isEqualTo(Map.of("afterSec", 200L));
    }

    @Test
    @DisplayName("triggerType 변경 — 스냅샷이 같아도 리셋")
    void typeChangeResets() {
        AppTriggerSubscription s = polling(Map.of("query", "from:x"));

        s.reconfigure(TriggerType.GITHUB_NEW_ISSUE, s.getUserId(), null, Map.of("query", "from:x"), PollStart.NONE);

        assertThat(s.getTriggerType()).isEqualTo(TriggerType.GITHUB_NEW_ISSUE);
        assertThat(s.getCursor()).isNull();
        assertThat(s.getNextPollAt()).isNull();
    }
}
