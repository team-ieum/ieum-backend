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

    @Test
    @DisplayName("appTriggerOf — 노드 config 값에서 앱 트리거만 골라낸다(그 외·모르는 값·비문자열은 null)")
    void appTriggerOf() {
        assertThat(TriggerType.appTriggerOf("GMAIL_NEW_EMAIL")).isEqualTo(TriggerType.GMAIL_NEW_EMAIL);
        assertThat(TriggerType.appTriggerOf("GITHUB_NEW_ISSUE")).isEqualTo(TriggerType.GITHUB_NEW_ISSUE);
        assertThat(TriggerType.appTriggerOf("SCHEDULE")).isNull();
        assertThat(TriggerType.appTriggerOf("gmail_new_email")).isNull();
        assertThat(TriggerType.appTriggerOf("UNKNOWN")).isNull();
        assertThat(TriggerType.appTriggerOf(null)).isNull();
        assertThat(TriggerType.appTriggerOf(1)).isNull();
    }
}
