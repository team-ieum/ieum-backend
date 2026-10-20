package com.ieum.api.workflow.queue;

import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.api.deployment.DeploymentControl;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository.PendingObservation;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recover committed executions whose initial XADD was lost or whose publishing process died. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ieum.deployment.distributed.enabled", havingValue = "true")
public class PendingExecutionRequeuer {
    private final WorkflowExecutionRepository executions;
    private final ExecutionJobQueue queue;
    private final DeploymentControl control;
    @Value("${ieum.workflow.queue.pending-recovery-not-before:}")
    private String notBefore = "";
    @Value("${ieum.workflow.queue.pending-requeue-grace:PT1M}")
    private Duration grace = Duration.ofMinutes(1);
    @Value("${ieum.workflow.queue.pending-recovery-max-age:PT1H}")
    private Duration maxAge = Duration.ofHours(1);
    @Value("${ieum.workflow.queue.pending-recovery-max-attempts:3}")
    private int maxAttempts = 3;
    @Value("${ieum.workflow.queue.pending-recovery-observation-interval:PT1M}")
    private Duration observationInterval = Duration.ofMinutes(1);
    @Autowired(required = false)
    private MeterRegistry metrics;
    private Clock clock = Clock.systemDefaultZone();
    private Instant nextObservation = Instant.MIN;
    private final ExcludedSample beforeCutoff = new ExcludedSample("before_cutoff");
    private final ExcludedSample agedOut = new ExcludedSample("age");
    private final AtomicLong exhaustedLastBatch = new AtomicLong();
    private final AtomicLong deferredLastBatch = new AtomicLong();
    private final AtomicLong observedAtEpochSeconds = new AtomicLong();
    private boolean metersRegistered;
    private LocalDateTime cutoff;
    private int page;
    private static final int PAGE_SIZE = 100;

    private static final class ExcludedSample {
        final String reason;
        final AtomicLong sampled = new AtomicLong();
        final AtomicLong limitReached = new AtomicLong();
        final AtomicLong oldestAgeSeconds = new AtomicLong();
        ExcludedSample(String reason) { this.reason = reason; }
        void reset() { sampled.set(0); limitReached.set(0); oldestAgeSeconds.set(0); }
    }

    @PostConstruct
    void validatePolicy() {
        try {
            cutoff = LocalDateTime.ofInstant(OffsetDateTime.parse(notBefore).toInstant(), clock.getZone());
        } catch (Exception e) {
            throw new IllegalStateException("Distributed pending recovery requires an explicit ISO-8601 offset/Z "
                + "ieum.workflow.queue.pending-recovery-not-before after operator review", e);
        }
        if (grace.isNegative() || grace.isZero() || maxAge.compareTo(grace) <= 0
                || maxAttempts < 1 || maxAttempts > 100 || observationInterval.compareTo(Duration.ofSeconds(20)) < 0) {
            throw new IllegalStateException("Invalid pending recovery age/grace/attempt policy");
        }
        registerMeters();
        log.info("[PendingExecutionRequeuer] 복구 정책 — cutoff: {}, local timestamp zone: {}, max age: {}, "
                + "attempt budget: {}; 제외된 PENDING은 자동 실패/재실행하지 않는다",
            notBefore, clock.getZone(), maxAge, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${ieum.workflow.queue.pending-requeue-interval:PT20S}")
    public synchronized void recoverPending() {
        if (!control.isOwner()) {
            resetGauges();
            nextObservation = Instant.MIN;
            return;
        }
        if (cutoff == null) throw new IllegalStateException("Pending recovery policy was not initialized");
        exhaustedLastBatch.set(0);
        deferredLastBatch.set(0);
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            observeExcluded(now);
            LocalDateTime lower = now.minus(maxAge).isAfter(cutoff) ? now.minus(maxAge) : cutoff;
            LocalDateTime upper = now.minus(grace);
            if (!lower.isBefore(upper)) return;
            var ids = executions.findPendingIdsBetween(lower, upper,
                PageRequest.of(page, PAGE_SIZE));
            // Continue past blocked old rows rather than fetching the first page forever. Changing
            // statuses can move rows between pages; the next complete pass revisits anything skipped.
            page = ids.size() == PAGE_SIZE ? page + 1 : 0;
            int exhausted = 0, deferred = 0;
            for (var id : ids) {
                if (!control.isOwner()) break;
                var result = queue.requeuePending(id, maxAttempts);
                if (metrics != null && result != null) metrics.counter("ieum.workflow.pending.recovery.results",
                    "result", result.name().toLowerCase(java.util.Locale.ROOT)).increment();
                if (result == ExecutionJobQueue.RecoveryResult.ATTEMPTS_EXHAUSTED) exhausted++;
                if (result == ExecutionJobQueue.RecoveryResult.DEFERRED) deferred++;
            }
            exhaustedLastBatch.set(exhausted);
            deferredLastBatch.set(deferred);
            if (exhausted > 0 || deferred > 0)
                log.warn("[PendingExecutionRequeuer] 자동 복구 보류 — attempts exhausted: {}, queue uncertain: {}",
                    exhausted, deferred);
        } catch (Exception e) {
            if (metrics != null) metrics.counter("ieum.workflow.pending.recovery.failures").increment();
            log.warn("[PendingExecutionRequeuer] PENDING 복구 실패 — 다음 주기 재시도", e);
        }
    }

    private void observeExcluded(LocalDateTime now) {
        if (clock.instant().isBefore(nextObservation)) return;
        nextObservation = clock.instant().plus(observationInterval);
        try {
            if (!control.isOwner()) return;
            var historical = executions.findPendingObservationsBefore(cutoff, PageRequest.of(0, PAGE_SIZE + 1));
            if (!control.isOwner()) { resetGauges(); return; }
            LocalDateTime ageLimit = now.minus(maxAge);
            var old = cutoff.isBefore(ageLimit)
                ? executions.findPendingObservationsBetween(cutoff, ageLimit, PageRequest.of(0, PAGE_SIZE + 1))
                : List.<PendingObservation>of();
            if (!control.isOwner()) { resetGauges(); return; }
            updateSample(beforeCutoff, historical, now);
            updateSample(agedOut, old, now);
            observedAtEpochSeconds.set(clock.instant().getEpochSecond());
        } catch (Exception e) {
            // Keep the last successful gauges on a failed observation; a separate counter tells the
            // operator that this snapshot is stale. Failure must not prevent in-window recovery.
            if (metrics != null) metrics.counter("ieum.workflow.pending.recovery.observation.failures").increment();
            log.warn("[PendingExecutionRequeuer] 복구 창 밖 PENDING 관측 실패 — 기존 관측값 유지", e);
        }
    }

    private void updateSample(ExcludedSample sample, List<PendingObservation> rows, LocalDateTime now) {
        long prior = sample.sampled.get();
        sample.sampled.set(Math.min(PAGE_SIZE, rows.size()));
        sample.limitReached.set(rows.size() > PAGE_SIZE ? 1 : 0);
        sample.oldestAgeSeconds.set(rows.isEmpty() ? 0
            : Math.max(0, Duration.between(rows.getFirst().getStartedAt(), now).getSeconds()));
        if (!rows.isEmpty()) {
            log.warn("[PendingExecutionRequeuer] 운영자 검토 필요 — excluded reason: {}, sampled: {}{}, "
                    + "oldest execution: {}, oldest startedAt: {}, oldest age seconds: {}; "
                    + "자동 FAILED/삭제/재실행 없이 PENDING 유지, SCHEDULE 중복 방지 조건 유지",
                sample.reason, sample.sampled.get(), sample.limitReached.get() == 1 ? "+" : "",
                rows.getFirst().getId(), rows.getFirst().getStartedAt(), sample.oldestAgeSeconds.get());
        } else if (prior > 0) {
            log.info("[PendingExecutionRequeuer] 제외 PENDING 관측 정상화 — reason: {}", sample.reason);
        }
    }

    private void registerMeters() {
        if (metrics == null || metersRegistered) return;
        metersRegistered = true;
        for (var sample : List.of(beforeCutoff, agedOut)) {
            Gauge.builder("ieum.workflow.pending.recovery.excluded.sampled", sample.sampled, AtomicLong::get)
                .tag("reason", sample.reason).description("Bounded sample of excluded PENDING rows, capped at 100")
                .register(metrics);
            Gauge.builder("ieum.workflow.pending.recovery.excluded.limit_reached", sample.limitReached, AtomicLong::get)
                .tag("reason", sample.reason).register(metrics);
            Gauge.builder("ieum.workflow.pending.recovery.excluded.oldest_age_seconds", sample.oldestAgeSeconds, AtomicLong::get)
                .tag("reason", sample.reason).register(metrics);
        }
        Gauge.builder("ieum.workflow.pending.recovery.batch", exhaustedLastBatch, AtomicLong::get)
            .tag("result", "attempts_exhausted").register(metrics);
        Gauge.builder("ieum.workflow.pending.recovery.batch", deferredLastBatch, AtomicLong::get)
            .tag("result", "deferred").register(metrics);
        Gauge.builder("ieum.workflow.pending.recovery.observed_at_epoch_seconds", observedAtEpochSeconds,
            AtomicLong::get).register(metrics);
    }

    private void resetGauges() {
        beforeCutoff.reset(); agedOut.reset();
        exhaustedLastBatch.set(0); deferredLastBatch.set(0);
        observedAtEpochSeconds.set(0);
    }
}
