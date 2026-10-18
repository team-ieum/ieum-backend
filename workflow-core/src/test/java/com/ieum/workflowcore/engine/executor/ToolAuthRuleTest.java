package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ToolAuthRuleTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "builtin:google_calendar_create, google_oauth",
        "builtin:google_sheets_read,     google_oauth",
        "builtin:notion_search,          notion_token",
        "builtin:github_create_issue,    github_token",
        "slack,                          webhook_credential",
        "discord,                        webhook_credential",
        // 웹훅은 이름 정확 일치 — 접두사로 보면 slack_x도 웹훅 URL을 받는다
        "slack_x,",
        "builtin:slack_send,",
        // gmail.send_email(builder=false)의 tool_key — 실행기 어디서도 토큰을 싣지 않는다
        "gmail,",
        "builtin:google,",
        ","
    })
    @DisplayName("tool_key → 실행 때 받는 인증. 규칙 밖이면 null")
    void authOf(String toolKey, String expected) {
        assertThat(ToolAuthRule.authOf(toolKey)).isEqualTo(expected);
    }

    @Test
    @DisplayName("authOf가 돌려주는 값은 전부 KNOWN_AUTHS 안에 있다")
    void authOf_valuesAreKnown() {
        for (String key : new String[]{"builtin:google_x", "builtin:notion_x", "builtin:github_x", "slack", "discord"}) {
            assertThat(ToolAuthRule.KNOWN_AUTHS).contains(ToolAuthRule.authOf(key));
        }
        assertThat(ToolAuthRule.KNOWN_AUTHS).hasSize(4);
    }
}
