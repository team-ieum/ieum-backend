package com.ieum.api.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.alert.DiscordWebhookSender.SendResult;
import com.ieum.workflowcore.domain.ApprovalGate;
import com.ieum.workflowcore.engine.executor.WebhookCredentialProvider;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ApprovalNotifierTest {

    private static final String DISCORD = "https://discord.com/api/webhooks/1/secret";

    private final DiscordWebhookSender sender = mock(DiscordWebhookSender.class);
    private final WebhookCredentialProvider provider = mock(WebhookCredentialProvider.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApprovalNotifier notifier = new ApprovalNotifier(sender, provider, objectMapper);

    private final UUID userId = UUID.randomUUID();
    private final UUID webhookId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(notifier, "baseUrl", "");
        given(provider.resolveWebhookUrl(webhookId, userId)).willReturn(Optional.of(DISCORD));
    }

    private ApprovalGate gate(String event, String message) throws Exception {
        String view = objectMapper.writeValueAsString(Map.of("event", event, "message", message));
        ApprovalGate g = ApprovalGate.open(UUID.randomUUID(), UUID.randomUUID(), userId, "g", event, view,
            message, webhookId, LocalDateTime.of(2026, 11, 10, 9, 30), null);
        ReflectionTestUtils.setField(g, "id", UUID.randomUUID());
        return g;
    }

    private String sent() {
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(sender).sendOnce(eq(DISCORD), content.capture());
        return content.getValue();
    }

    @Test
    @DisplayName("첫 알림·리마인더·값 입력 문구 — 제목 줄, message, 기한 줄")
    void 문구_모양() throws Exception {
        notifier.notifyRequested(gate("approval.request", "확인해 주세요"), "주간 보고");
        String content = sent();
        assertThat(content.split("\n")).startsWith("🙋 승인 요청 — 주간 보고", "확인해 주세요");
        assertThat(content).contains("기한: ");

        org.mockito.Mockito.reset(sender);
        notifier.notifyReminder(gate("approval.collect", "입력"), "주간 보고");
        assertThat(sent()).startsWith("⏰ 다시 알려드려요 · ✍️ 값 입력 요청 — 주간 보고");
    }

    @Test
    @DisplayName("링크는 base-url이 있을 때만 넣는다")
    void 링크는_base_url이_있을_때만() throws Exception {
        ApprovalGate g = gate("approval.request", "확인");
        notifier.notifyRequested(g, "wf");
        assertThat(sent()).doesNotContain("/approvals/");

        org.mockito.Mockito.reset(sender);
        ReflectionTestUtils.setField(notifier, "baseUrl", "https://ieum.app");
        notifier.notifyRequested(g, "wf");
        assertThat(sent()).endsWith("https://ieum.app/approvals/" + g.getId());
    }

    @Test
    @DisplayName("문구는 2,000자 안에서 message만 자른다 — 기한·링크 줄은 남고 서로게이트 쌍을 가르지 않는다")
    void 문구는_2000자에서_자른다() throws Exception {
        ReflectionTestUtils.setField(notifier, "baseUrl", "https://ieum.app");
        // 이모지(서로게이트 쌍)가 자르는 자리에 걸리도록 홀짝을 섞는다
        ApprovalGate g = gate("approval.request", "가" + "😀".repeat(1_000));
        notifier.notifyRequested(g, "wf");

        String content = sent();
        assertThat(content.length()).isLessThanOrEqualTo(2_000);
        assertThat(content).contains("…\n기한: 2026-11-10").endsWith("https://ieum.app/approvals/" + g.getId());
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (Character.isHighSurrogate(c)) assertThat(Character.isLowSurrogate(content.charAt(i + 1))).isTrue();
            if (Character.isLowSurrogate(c)) assertThat(Character.isHighSurrogate(content.charAt(i - 1))).isTrue();
        }
    }

    @Test
    @DisplayName("웹훅을 풀지 못하면(없음·남의 것) 보내지 않는다")
    void 남의_웹훅은_보내지_않는다() throws Exception {
        given(provider.resolveWebhookUrl(any(), any())).willReturn(Optional.empty());

        assertThat(notifier.notifyRequested(gate("approval.request", "확인"), "wf")).isEqualTo(SendResult.PERMANENT);

        verify(sender, never()).sendOnce(anyString(), anyString());
    }

    @Test
    @DisplayName("푼 주소가 Discord 웹훅이 아니면(Slack 등) 보내지 않는다")
    void 디스코드_주소가_아니면_보내지_않는다() throws Exception {
        given(provider.resolveWebhookUrl(webhookId, userId))
            .willReturn(Optional.of("https://hooks.slack.com/services/T/B/x"));

        assertThat(notifier.notifyRequested(gate("approval.request", "확인"), "wf")).isEqualTo(SendResult.PERMANENT);

        verify(sender, never()).sendOnce(anyString(), anyString());
    }

    @Test
    @DisplayName("웹훅 조회가 예외를 던져도 밖으로 던지지 않는다")
    void 예외를_던지지_않는다() throws Exception {
        given(provider.resolveWebhookUrl(any(), any())).willThrow(new IllegalStateException("x"));

        assertThat(notifier.notifyRequested(gate("approval.request", "확인"), "wf")).isEqualTo(SendResult.PERMANENT);

        verify(sender, never()).sendOnce(anyString(), anyString());
    }

    @Test
    @DisplayName("발송 결과를 그대로 돌려준다 — 429면 RETRYABLE")
    void 발송_결과를_돌려준다() throws Exception {
        given(sender.sendOnce(eq(DISCORD), anyString())).willReturn(SendResult.SENT, SendResult.RETRYABLE);
        ApprovalGate g = gate("approval.request", "확인");

        assertThat(notifier.notifyRequested(g, "wf")).isEqualTo(SendResult.SENT);
        assertThat(notifier.notifyReminder(g, "wf")).isEqualTo(SendResult.RETRYABLE);
    }
}
