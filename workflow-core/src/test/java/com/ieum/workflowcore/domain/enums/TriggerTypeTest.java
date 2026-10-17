package com.ieum.workflowcore.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TriggerTypeTest {

    @Test
    @DisplayName("앱 트리거 판정 — Gmail·GitHub만 앱 트리거다")
    void appTriggers() {
        assertThat(TriggerType.GMAIL_NEW_EMAIL.isAppTrigger()).isTrue();
        assertThat(TriggerType.GITHUB_NEW_ISSUE.isAppTrigger()).isTrue();
        assertThat(TriggerType.MANUAL.isAppTrigger()).isFalse();
        assertThat(TriggerType.WEBHOOK.isAppTrigger()).isFalse();
        assertThat(TriggerType.SCHEDULE.isAppTrigger()).isFalse();
    }
}
