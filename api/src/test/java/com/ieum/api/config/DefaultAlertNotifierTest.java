package com.ieum.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ieum.api.alert.AlertCooldownStore;
import com.ieum.api.alert.DiscordWebhookSender;
import com.ieum.workflowcore.engine.executor.AlertNotifier.ExecutionFailureAlert;
import com.ieum.workflowcore.engine.executor.WebhookCredentialProvider;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class DefaultAlertNotifierTest {

    private static final String OPS_URL = "https://discord.com/api/webhooks/ops/opsSecret";
    private static final String OWNER_URL = "https://discord.com/api/webhooks/owner/ownerSecret";

    private AlertCooldownStore cooldownStore;
    private DiscordWebhookSender sender;
    private WebhookCredentialProvider webhookCredentialProvider;
    private DefaultAlertNotifier notifier;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        cooldownStore = Mockito.mock(AlertCooldownStore.class);
        sender = Mockito.mock(DiscordWebhookSender.class);
        webhookCredentialProvider = Mockito.mock(WebhookCredentialProvider.class);
        notifier = new DefaultAlertNotifier(cooldownStore, sender, webhookCredentialProvider);

        when(cooldownStore.tryAcquire(workflowId)).thenReturn(true);
        setOpsUrl(OPS_URL);
    }

    private void setOpsUrl(String url) {
        ReflectionTestUtils.setField(notifier, "opsWebhookUrl", url);
    }

    private ExecutionFailureAlert alert(String failedNodeId, String errorSummary,
                                       boolean retryExhausted) {
        return new ExecutionFailureAlert(executionId, workflowId, "주문 처리", ownerId,
                failedNodeId, errorSummary, retryExhausted);
    }

    @Test
    @DisplayName("FAILED 시 운영자·소유자 양쪽으로 발신된다")
    void notify_sendsToOpsAndOwner() {
        when(webhookCredentialProvider.resolveAlertWebhookUrl(ownerId))
                .thenReturn(Optional.of(OWNER_URL));

        notifier.notifyExecutionFailed(alert("node-a", "타임아웃", false));

        verify(sender).send(eq(OPS_URL), anyString());
        verify(sender).send(eq(OWNER_URL), anyString());
    }

    @Test
    @DisplayName("DISCORD_OPS_WEBHOOK_URL 미설정이면 운영자 발신을 시도하지 않는다 — 빈 URL로도 보내지 않는다")
    void notify_opsUrlUnset_skipsOps() {
        setOpsUrl("");
        when(webhookCredentialProvider.resolveAlertWebhookUrl(ownerId))
                .thenReturn(Optional.of(OWNER_URL));

        notifier.notifyExecutionFailed(alert("node-a", "타임아웃", false));

        // 소유자 발신 딱 한 번뿐 — 빈 URL로 발신을 시도하는 것도 잡는다.
        verify(sender, times(1)).send(anyString(), anyString());
        verify(sender).send(eq(OWNER_URL), anyString());
    }

    @Test
    @DisplayName("운영자·소유자 둘 다 미설정이면 아무것도 발신하지 않는다 (로컬·테스트에서 조용히 꺼짐)")
    void notify_noTargetsAtAll_sendsNothing() {
        setOpsUrl("");
        when(webhookCredentialProvider.resolveAlertWebhookUrl(ownerId)).thenReturn(Optional.empty());

        notifier.notifyExecutionFailed(alert("node-a", "타임아웃", false));

        verify(sender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("alertTarget 지정이 없으면 소유자 발신을 생략한다")
    void notify_noAlertTarget_skipsOwner() {
        when(webhookCredentialProvider.resolveAlertWebhookUrl(ownerId)).thenReturn(Optional.empty());

        notifier.notifyExecutionFailed(alert("node-a", "타임아웃", false));

        // 운영자 발신 딱 한 번뿐
        verify(sender, times(1)).send(anyString(), anyString());
        verify(sender).send(eq(OPS_URL), anyString());
    }

    @Test
    @DisplayName("쿨다운 중이면 운영자·소유자 어디에도 발신하지 않고 대상 조회조차 하지 않는다")
    void notify_withinCooldown_sendsNothing() {
        when(cooldownStore.tryAcquire(workflowId)).thenReturn(false);

        notifier.notifyExecutionFailed(alert("node-a", "타임아웃", false));

        verify(sender, never()).send(anyString(), anyString());
        verify(webhookCredentialProvider, never()).resolveAlertWebhookUrl(ownerId);
    }

    @Test
    @DisplayName("소유자 웹훅 조회가 예외를 던져도 운영자 발신은 유지되고 예외가 새지 않는다")
    void notify_ownerLookupThrows_isIsolated() {
        when(webhookCredentialProvider.resolveAlertWebhookUrl(ownerId))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> notifier.notifyExecutionFailed(alert("node-a", "타임아웃", false)))
                .doesNotThrowAnyException();

        verify(sender).send(eq(OPS_URL), anyString());
    }

    @Test
    @DisplayName("메시지에 워크플로우명·실행ID·실패 노드·오류 요약이 담긴다")
    void message_containsAllowedFieldsOnly() {
        when(webhookCredentialProvider.resolveAlertWebhookUrl(ownerId)).thenReturn(Optional.empty());

        notifier.notifyExecutionFailed(alert("node-a", "HTTP 500 from api.example.com", false));

        assertThat(capturedMessage())
                .contains("주문 처리")
                .contains(executionId.toString())
                .contains("node-a")
                .contains("HTTP 500 from api.example.com")
                .contains("워크플로우 실행이 실패했습니다.");
    }

    @Test
    @DisplayName("retryExhausted면 재시도 소진 문구로 구분된다")
    void message_retryExhausted_usesDistinctWording() {
        when(webhookCredentialProvider.resolveAlertWebhookUrl(ownerId)).thenReturn(Optional.empty());

        notifier.notifyExecutionFailed(alert("node-a", "rate limit", true));

        assertThat(capturedMessage()).contains("재시도를 모두 소진하고 실패했습니다.");
    }

    @Test
    @DisplayName("실패 노드를 특정할 수 없으면 실패 노드 줄을 넣지 않는다")
    void message_nullNodeId_omitsNodeLine() {
        when(webhookCredentialProvider.resolveAlertWebhookUrl(ownerId)).thenReturn(Optional.empty());

        notifier.notifyExecutionFailed(alert(null, "정의 없음", false));

        assertThat(capturedMessage()).doesNotContain("실패 노드");
    }

    @Test
    @DisplayName("긴 오류 요약은 200자로 절단된다 — Discord content 상한을 넘기지 않는다")
    void message_longErrorSummary_isTruncated() {
        when(webhookCredentialProvider.resolveAlertWebhookUrl(ownerId)).thenReturn(Optional.empty());
        String longError = "x".repeat(500);

        notifier.notifyExecutionFailed(alert("node-a", longError, false));

        String message = capturedMessage();
        assertThat(message).contains("x".repeat(200) + "…");
        assertThat(message).doesNotContain("x".repeat(201));
    }

    private String capturedMessage() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(OPS_URL), captor.capture());
        return captor.getValue();
    }
}
