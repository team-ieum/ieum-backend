package com.ieum.api.trigger.forms;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.GoogleFormsReader;
import com.ieum.api.integration.options.GoogleFormsReader.FormQuestion;
import com.ieum.api.trigger.AppTriggerFirer;
import com.ieum.common.exception.CustomException;
import com.ieum.workflowcore.deployment.BackgroundOwnership;
import com.ieum.workflowcore.domain.AppTriggerSubscription;
import com.ieum.workflowcore.service.AppTriggerSubscriptionService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Forms 트리거 폴링(T1·T2). 1분마다 종류별로 기한이 된 구독을 클레임하고, 행마다
 * {@code timestamp >= lowSec}(Forms는 lastSubmittedTime을 본다) → {@code eventSec >= lowSec}·미발화 응답을
 * {@code (eventSec, responseId)} 순으로 최대 20건 발화 → 커서 기록. T1의 eventSec은 createTime이라 연결 전에 만든 응답이
 * 수정돼 목록에 다시 나와도 발화하지 않는다.
 *
 * <p>at-least-once({@code GmailTriggerSweeper}와 같다). 트랜잭션을 걸지 않는다. 응답 내용·폼 ID는 로그에 남기지 않는다.
 */
@Slf4j
@Component
public class FormsTriggerSweeper {

    static final int CLAIM_LIMIT = 50;
    static final int MAX_FIRES_PER_POLL = 20;
    /** 폴링이 이보다 오래 실패만 했으면 지금부터 다시 본다 — 재연동 순간 밀린 응답 전부가 발화하지 않게 */
    static final long MAX_CATCHUP_SEC = Duration.ofHours(24).toSeconds();

    @Autowired(required = false)
    private BackgroundOwnership backgroundOwnership;

    private final AppTriggerSubscriptionService subscriptions;
    private final GoogleFormsReader reader;
    private final List<FormsTriggerHandler> handlers;
    private final AppTriggerFirer firer;
    private final Duration pollInterval;

    public FormsTriggerSweeper(AppTriggerSubscriptionService subscriptions, GoogleFormsReader reader,
                               List<FormsTriggerHandler> handlers, AppTriggerFirer firer,
                               @Value("${ieum.trigger.forms.poll-interval:PT5M}") Duration pollInterval) {
        this.subscriptions = subscriptions;
        this.reader = reader;
        this.handlers = handlers;
        this.firer = firer;
        this.pollInterval = pollInterval;
    }

    /** 초기 지연은 테스트가 크게 잡는다 — 캐시된 컨텍스트의 스윕이 공유 H2의 다른 테스트 행을 리스하지 않게 */
    @Scheduled(fixedDelay = 60_000, initialDelayString = "${ieum.trigger.forms.sweep-initial-delay:PT1M}")
    public void sweep() {
        if (backgroundOwnership != null && !backgroundOwnership.isOwner()) {
            return;
        }
        for (FormsTriggerHandler handler : handlers) {
            subscriptions.claimDue(handler.type(), LocalDateTime.now(), CLAIM_LIMIT, pollInterval)
                .forEach(s -> pollOne(handler, s));
        }
    }

    private void pollOne(FormsTriggerHandler handler, AppTriggerSubscription subscription) {
        long now = Instant.now().getEpochSecond();
        FormsCursor cursor = FormsCursor.from(subscription.getCursor(), now);
        if (cursor.okSec() < now - MAX_CATCHUP_SEC) {
            log.info("[FormsTriggerSweeper] 폴링이 {}초 동안 실패해 지금부터 다시 본다 — subscriptionId: {}",
                now - cursor.okSec(), subscription.getId());
            cursor = FormsCursor.from(null, now);
        }
        String error = null;
        try {
            poll(handler, subscription, cursor);
            cursor.markOk(now);
        } catch (Exception e) {
            error = summarize(e);
            log.warn("[FormsTriggerSweeper] 폴링 실패 — subscriptionId: {}, workflowId: {}, error: {}",
                subscription.getId(), subscription.getWorkflowId(), error);
        }
        // 실패해도 그때까지 발화한 지점까지는 기록한다 — 되돌리면 다음 폴링이 같은 응답으로 다시 발화한다
        try {
            subscriptions.recordPoll(subscription.getId(), subscription.getConfig(), cursor.toMap(), error,
                LocalDateTime.now().plus(pollInterval));
        } catch (RuntimeException e) {
            log.error("[FormsTriggerSweeper] 폴링 기록 실패 — subscriptionId: {}", subscription.getId(), e);
        }
    }

    private void poll(FormsTriggerHandler handler, AppTriggerSubscription subscription, FormsCursor cursor) {
        // 구독 config = 스냅샷 — 키는 formId(노드 config의 form_id가 아니다). 작성 중 구독은 null
        if (!(subscription.getConfig().get("formId") instanceof String formId)) {
            return;
        }
        UUID userId = subscription.getUserId();
        long lowSec = cursor.lowSec();
        // 후보는 시각만 받은 가벼운 목록으로 고른다 — 밀린 응답 본문 전체를 매 폴링 받으면 수천 건에서 힙이 터진다
        List<JsonNode> batch = reader.listResponses(userId, formId, handler.filter(lowSec),
                FormsTriggerHandler.LIGHT_FIELDS).stream()
            .filter(r -> {
                Long sec = handler.eventSec(r);   // 시각 형식이 어긋난 응답은 건너뛴다
                return sec != null && sec >= lowSec && !cursor.seen(handler.dedupeKey(r));
            })
            .sorted(Comparator.comparing((JsonNode r) -> handler.eventSec(r))
                .thenComparing(r -> r.path("responseId").asText()))
            .limit(MAX_FIRES_PER_POLL)
            .toList();
        if (batch.isEmpty()) {
            return;
        }
        List<FormQuestion> questions = reader.questions(userId, formId);
        for (JsonNode candidate : batch) {
            // 본문은 고른 건만 단건으로 받는다 — 커서 키·시각은 목록 값 그대로
            Optional<JsonNode> response = reader.response(userId, formId, candidate.path("responseId").asText());
            if (response.isEmpty()) {
                // 404(삭제·목록과 어긋난 유령)는 seen에 넣어 다음 폴링 후보에서 뺀다 — 안 넣으면 유령 20건이 배치를 채워
                // 커서가 영영 멈춘다(폴링은 성공이라 24시간 리셋도 안 걸린다). 커서 시각은 발화한 것만 기준.
                // ponytail: GoogleApiReader가 403·404를 같은 GOOGLE_RESOURCE_NOT_FOUND로 줘 구분할 수 없어 단건 403도 seen에
                // 넣는다(그 건은 다시 보지 않음). 권한 상실은 앞선 목록 조회가 먼저 실패하고 Forms 쿼터는 429라 실제 경로는
                // 없다고 본다 — 단건 403을 다시 보려면 GoogleApiReader가 403을 따로 내야 한다.
                cursor.skip(handler.dedupeKey(candidate), handler.eventSec(candidate));
                continue;
            }
            if (!firer.fire(subscription.getWorkflowId(), handler.type(), handler.output(response.get(), questions))) {
                return; // 비활성·삭제 — sync가 곧 행을 지운다
            }
            cursor.advance(handler.dedupeKey(candidate), handler.eventSec(candidate));
        }
    }

    private static String summarize(Exception e) {
        return e instanceof CustomException ce
            ? ce.getErrorCode().name() + ": " + ce.getMessage()
            : e.getClass().getSimpleName();
    }
}
