package com.ieum.api.config;

import com.ieum.api.alert.AlertCooldownStore;
import com.ieum.api.alert.DiscordWebhookSender;
import com.ieum.workflowcore.engine.executor.AlertNotifier;
import com.ieum.workflowcore.engine.executor.WebhookCredentialProvider;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * AlertNotifier 실제 구현체 (어댑터). 실패 알림을 운영자·소유자 Discord 채널로 보낸다.
 *
 * <p>{@code @Primary}로 스캔 순서와 무관하게 이 빈이 선택되도록 고정한다(다른 Provider 포트와 동일).
 *
 * <p>책임은 대상 결정과 문구 구성까지다 — 쿨다운은 {@link AlertCooldownStore},
 * HTTP 발신은 {@link DiscordWebhookSender}가 맡는다.
 *
 * <p>운영자 웹훅은 {@code DISCORD_OPS_WEBHOOK_URL}이 비어 있으면 발신을 시도하지 않는다
 * (로컬·테스트에서 조용히 꺼짐). 소유자 웹훅은 알림 대상 지정이 없으면 생략한다.
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class DefaultAlertNotifier implements AlertNotifier {

    /** Discord content 상한은 2000자다. 오류 요약은 그 훨씬 아래에서 자른다. */
    private static final int MAX_ERROR_SUMMARY_LENGTH = 200;

    private final AlertCooldownStore cooldownStore;
    private final DiscordWebhookSender sender;
    private final WebhookCredentialProvider webhookCredentialProvider;

    /** 운영자 알림 채널. 미설정(빈 문자열)이면 운영자 발신은 no-op. */
    @Value("${DISCORD_OPS_WEBHOOK_URL:}")
    private String opsWebhookUrl;

    @Override
    public void notifyExecutionFailed(ExecutionFailureAlert alert) {
        if (alert == null) {
            return;
        }
        if (!cooldownStore.tryAcquire(alert.workflowId())) {
            log.info("[AlertNotifier] 쿨다운 중 — 알림 생략. workflowId: {}, executionId: {}",
                    alert.workflowId(), alert.executionId());
            return;
        }

        String content = buildMessage(alert);

        if (opsWebhookUrl != null && !opsWebhookUrl.isBlank()) {
            sender.send(opsWebhookUrl, content);
        }

        // 소유자 대상 지정이 없으면 빈 Optional — 소유자 발신을 생략한다.
        Optional<String> ownerUrl = resolveOwnerWebhookUrl(alert);
        ownerUrl.ifPresent(url -> sender.send(url, content));
    }

    /**
     * 소유자 알림 웹훅 조회. 조회 자체가 실패해도(복호 실패, DB 장애) 운영자 발신은 이미 끝났고
     * 예외를 올려보내면 실행 종료 처리를 위협하므로 여기서 삼킨다.
     */
    private Optional<String> resolveOwnerWebhookUrl(ExecutionFailureAlert alert) {
        try {
            return webhookCredentialProvider.resolveAlertWebhookUrl(alert.ownerUserId());
        } catch (Exception e) {
            log.warn("[AlertNotifier] 소유자 알림 웹훅 조회 실패 — userId: {}", alert.ownerUserId(), e);
            return Optional.empty();
        }
    }

    /**
     * 알림 문구. 워크플로우명·실행ID·실패 노드·오류 요약까지만 담는다 —
     * 자격증명·API 키·프롬프트 원문을 넣지 말 것(채널로 그대로 새어 나간다).
     */
    private String buildMessage(ExecutionFailureAlert alert) {
        StringBuilder message = new StringBuilder()
                .append(alert.retryExhausted()
                        ? "재시도를 모두 소진하고 실패했습니다."
                        : "워크플로우 실행이 실패했습니다.")
                .append("\n워크플로우: ").append(alert.workflowName())
                .append("\n실행 ID: ").append(alert.executionId());
        if (alert.failedNodeId() != null) {
            message.append("\n실패 노드: ").append(alert.failedNodeId());
        }
        if (alert.errorSummary() != null && !alert.errorSummary().isBlank()) {
            message.append("\n오류: ").append(truncate(alert.errorSummary()));
        }
        return message.toString();
    }

    private String truncate(String value) {
        return value.length() <= MAX_ERROR_SUMMARY_LENGTH
                ? value
                : value.substring(0, MAX_ERROR_SUMMARY_LENGTH) + "…";
    }
}
