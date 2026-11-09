package com.ieum.api.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.alert.DiscordWebhookSender.SendResult;
import com.ieum.workflowcore.domain.ApprovalGate;
import com.ieum.workflowcore.engine.executor.ApprovalGateConfig;
import com.ieum.workflowcore.engine.executor.WebhookCredentialProvider;
import com.ieum.workflowcore.util.SensitiveDataMasker;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 승인 게이트 Discord 알림(첫 알림·리마인더). {@link com.ieum.api.workflow.service.ApprovalGateSweeper}가 선점한 뒤
 * 부른다. 예외를 던지지 않고 발송 결과({@link SendResult})를 돌려준다. 웹훅을 풀지 못하거나(없음·남의 것) Discord 웹훅이 아니면 건너뛴다 —
 * {@code resolveWebhookUrl}은 provider를 보지 않아 Slack 웹훅 ID도 풀린다. URL은 로그에 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalNotifier {

    static final int MAX_CONTENT = 2_000;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DEADLINE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DiscordWebhookSender sender;
    private final WebhookCredentialProvider webhookCredentialProvider;
    private final ObjectMapper objectMapper;

    /** 승인 화면 링크 주소. 비면 링크 줄을 뺀다. */
    @Value("${ieum.app.base-url:}")
    private String baseUrl;

    public SendResult notifyRequested(ApprovalGate gate, String workflowName) {
        return send(gate, workflowName, false);
    }

    public SendResult notifyReminder(ApprovalGate gate, String workflowName) {
        return send(gate, workflowName, true);
    }

    /** 웹훅을 못 풀거나 Discord 웹훅이 아니면 PERMANENT. 웹훅 조회의 DB 일시 오류만 RETRYABLE. */
    private SendResult send(ApprovalGate gate, String workflowName, boolean reminder) {
        try {
            if (gate.getWebhookId() == null) return SendResult.PERMANENT;
            Optional<String> url = webhookCredentialProvider.resolveWebhookUrl(gate.getWebhookId(), gate.getUserId());
            if (url.isEmpty() || !SensitiveDataMasker.isDiscordWebhookUrl(url.get())) {
                log.warn("[ApprovalNotifier] 보낼 수 있는 Discord 웹훅이 없어 건너뜀 — gateId: {}", gate.getId());
                return SendResult.PERMANENT;
            }
            return sender.sendOnce(url.get(), content(gate, workflowName, reminder));
        } catch (Exception e) {
            log.warn("[ApprovalNotifier] 알림 실패 — gateId: {}, cause: {}", gate.getId(), e.getClass().getSimpleName());
            return e instanceof DataAccessException ? SendResult.RETRYABLE : SendResult.PERMANENT;
        }
    }

    /** 머리줄·기한·링크를 먼저 만들고 남은 예산만큼만 message를 넣는다 — 긴 message가 기한·링크 줄을 밀어내지 않게. */
    private String content(ApprovalGate gate, String workflowName, boolean reminder) throws Exception {
        String head = (reminder ? "⏰ 다시 알려드려요 · " : "")
            + (ApprovalGateConfig.COLLECT.equals(gate.getEvent()) ? "✍️ 값 입력 요청 — " : "🙋 승인 요청 — ")
            + workflowName;
        String tail = "\n기한: " + gate.getDeadline().atZone(ZoneId.systemDefault())
            .withZoneSameInstant(KST).format(DEADLINE);
        if (baseUrl != null && !baseUrl.isBlank()) {
            tail += "\n" + baseUrl.replaceAll("/+$", "") + "/approvals/" + gate.getId();
        }
        head = cut(head, MAX_CONTENT - tail.length());
        Object message = objectMapper.readValue(gate.getViewJson(), Map.class).get("message");
        // 줄바꿈 1자를 뺀 남은 예산
        int budget = MAX_CONTENT - head.length() - tail.length() - 1;
        String body = message == null || budget <= 0 ? "" : "\n" + cut(String.valueOf(message), budget);
        return head + body + tail;
    }

    /** max자(UTF-16) 이하로 자른다. 넘치면 끝을 "…"로 — 서로게이트 쌍을 가르지 않는다. */
    static String cut(String s, int max) {
        if (s.length() <= max) return s;
        if (max <= 0) return "";
        int end = max - 1;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end) + "…";
    }
}
