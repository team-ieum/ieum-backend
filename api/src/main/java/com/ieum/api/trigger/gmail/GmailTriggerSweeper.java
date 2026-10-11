package com.ieum.api.trigger.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.trigger.AppTriggerFirer;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.AppTriggerSubscription;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.service.AppTriggerSubscriptionService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gmail New Email 트리거 폴링(spec §4.1). 1분마다 기한이 된 구독을 클레임(SKIP LOCKED + 리스)하고, 행마다
 * {@code (query) after:(lowSec-1)} → 미발화 메일을 오래된 순으로 최대 20건 발화 → 커서 기록.
 * {@code lowSec}은 lookback 창의 시작이다({@link GmailCursor} — 늦게 색인된 메일을 다시 본다).
 *
 * <p>{@code after:}의 경계 포함/제외와 무관하게 정확하다 — 질의는 1초 여유를 두고, 실제 수신 초({@code internalDate})로
 * 창 이전을 버린 뒤 {@code seen}으로 중복을 거른다.
 *
 * <p>배달 보장은 at-least-once다 — 발화 뒤 {@code recordPoll} 전에 프로세스가 죽으면 리스 만료 뒤 다시 발화한다.
 * 트랜잭션을 걸지 않는다(외부 호출·실행 생성을 묶지 않는다). 메일 내용·검색식은 로그에 남기지 않는다.
 */
@Slf4j
@Component
public class GmailTriggerSweeper {

    static final int CLAIM_LIMIT = 50;
    static final int MAX_FIRES_PER_POLL = 20;
    /** 폴링이 이보다 오래 실패만 했으면(연동 끊김 등) 지금부터 다시 본다 — 재연동 순간 밀린 메일 전부가 발화하지 않게. */
    static final long MAX_CATCHUP_SEC = Duration.ofHours(24).toSeconds();

    private final AppTriggerSubscriptionService subscriptions;
    private final GmailTriggerClient gmail;
    private final AppTriggerFirer firer;
    private final Duration pollInterval;

    public GmailTriggerSweeper(AppTriggerSubscriptionService subscriptions, GmailTriggerClient gmail,
                               AppTriggerFirer firer,
                               @Value("${ieum.trigger.gmail.poll-interval:PT5M}") Duration pollInterval) {
        this.subscriptions = subscriptions;
        this.gmail = gmail;
        this.firer = firer;
        this.pollInterval = pollInterval;
    }

    /** 초기 지연은 테스트가 크게 잡는다 — 캐시된 컨텍스트의 스윕러가 공유 H2의 다른 테스트 행을 리스하지 않게. */
    @Scheduled(fixedDelay = 60_000, initialDelayString = "${ieum.trigger.gmail.sweep-initial-delay:PT1M}")
    public void sweep() {
        List<AppTriggerSubscription> due = subscriptions.claimDue(
            TriggerType.GMAIL_NEW_EMAIL, LocalDateTime.now(), CLAIM_LIMIT, pollInterval);
        due.forEach(this::pollOne);
    }

    private void pollOne(AppTriggerSubscription subscription) {
        long now = Instant.now().getEpochSecond();
        GmailCursor cursor = GmailCursor.from(subscription.getCursor(), now);
        if (cursor.okSec() < now - MAX_CATCHUP_SEC) {
            log.info("[GmailTriggerSweeper] 폴링이 {}초 동안 실패해 지금부터 다시 본다 — subscriptionId: {}",
                now - cursor.okSec(), subscription.getId());
            cursor = GmailCursor.from(null, now);
        }
        String error = null;
        try {
            poll(subscription, cursor);
            cursor.markOk(now);
        } catch (Exception e) {
            error = summarize(e);
            log.warn("[GmailTriggerSweeper] 폴링 실패 — subscriptionId: {}, workflowId: {}, error: {}",
                subscription.getId(), subscription.getWorkflowId(), error);
        }
        // 실패해도 그때까지 발화한 지점까지는 기록한다 — 커서를 통째로 되돌리면 다음 폴링이 같은 메일로 다시 발화한다.
        try {
            subscriptions.recordPoll(subscription.getId(), subscription.getConfig(), cursor.toMap(), error,
                LocalDateTime.now().plus(pollInterval));
        } catch (RuntimeException e) {
            // 남은 클레임 행은 계속 폴링한다. 이 행은 리스 만료 뒤 같은 메일로 다시 발화한다(at-least-once)
            log.error("[GmailTriggerSweeper] 폴링 기록 실패 — subscriptionId: {}", subscription.getId(), e);
        }
    }

    private void poll(AppTriggerSubscription subscription, GmailCursor cursor) {
        String q = "(" + GmailTriggerClient.baseQuery(subscription.getConfig()) + ") after:" + (cursor.lowSec() - 1);
        List<String> ids = new ArrayList<>(gmail.listAllMessageIds(subscription.getUserId(), q));
        Collections.reverse(ids); // 목록은 최신순 — 오래된 것부터 본다

        List<JsonNode> batch = new ArrayList<>();
        int stale = 0;
        for (String id : ids) {
            if (batch.size() >= MAX_FIRES_PER_POLL) {
                break;
            }
            if (cursor.seen(id)) {
                continue;
            }
            // 403·404를 건너뛰지 않는다 — 둘 다 GOOGLE_RESOURCE_NOT_FOUND로 오고 rate limit도 403이라, 건너뛰면 그 메일은
            // 커서가 넘어가 영영 발화하지 않는다. 진짜 지워진 메일은 다음 목록에 안 나온다.
            JsonNode message = gmail.getMessage(subscription.getUserId(), id);
            if (GmailMessageNormalizer.internalDateSec(message) < cursor.lowSec()) {
                // 정상이면 after:(lowSec-1) 여유 1초분뿐이다. 많으면 검색식이 after: 제한을 벗어난 것 —
                // 메일함 전체를 매 폴링 단건 조회해 스윕을 독점하지 않게 잘못된 검색식과 같은 경로(오류 기록·커서 유지)로 끊는다.
                if (++stale > MAX_FIRES_PER_POLL) {
                    throw new CustomException(ErrorCode.INVALID_INPUT, "검색식이 after: 기간 제한을 벗어납니다.");
                }
                continue;
            }
            batch.add(message);
        }
        // Gmail 목록은 internalDate 역순이라 이 정렬은 배치 안의 방어일 뿐이다. 목록 순서가 어긋나면 20건 경계 너머의
        // 더 이른 메일은 커서에 밀린다(전 후보 날짜 조회는 적체 시 get이 최대 수백 회라 하지 않는다).
        batch.sort(Comparator.comparingLong(GmailMessageNormalizer::internalDateSec));

        for (JsonNode message : batch) {
            if (!firer.fire(subscription.getWorkflowId(), TriggerType.GMAIL_NEW_EMAIL,
                    GmailMessageNormalizer.normalize(message))) {
                return; // 비활성·삭제 — sync가 곧 행을 지운다
            }
            cursor.advance(message.path("id").asText(), GmailMessageNormalizer.internalDateSec(message));
        }
    }

    private static String summarize(Exception e) {
        return e instanceof CustomException ce
            ? ce.getErrorCode().name() + ": " + ce.getMessage()
            : e.getClass().getSimpleName();
    }
}
