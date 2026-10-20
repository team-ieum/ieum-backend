package com.ieum.api.workflow.queue;

import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.api.deployment.DeploymentControl;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
    private LocalDateTime cutoff;
    private int page;
    private static final int PAGE_SIZE = 100;

    @PostConstruct
    void validatePolicy() {
        try {
            cutoff = LocalDateTime.ofInstant(OffsetDateTime.parse(notBefore).toInstant(), ZoneId.systemDefault());
        } catch (Exception e) {
            throw new IllegalStateException("Distributed pending recovery requires an explicit ISO-8601 offset/Z "
                + "ieum.workflow.queue.pending-recovery-not-before after operator review", e);
        }
        if (grace.isNegative() || grace.isZero() || maxAge.compareTo(grace) <= 0
                || maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalStateException("Invalid pending recovery age/grace/attempt policy");
        }
    }

    @Scheduled(fixedDelayString = "${ieum.workflow.queue.pending-requeue-interval:PT20S}")
    public synchronized void recoverPending() {
        if (!control.isOwner()) return;
        if (cutoff == null) throw new IllegalStateException("Pending recovery policy was not initialized");
        try {
            LocalDateTime now = LocalDateTime.now();
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
                if (result == ExecutionJobQueue.RecoveryResult.ATTEMPTS_EXHAUSTED) exhausted++;
                if (result == ExecutionJobQueue.RecoveryResult.DEFERRED) deferred++;
            }
            if (exhausted > 0 || deferred > 0)
                log.warn("[PendingExecutionRequeuer] 자동 복구 보류 — attempts exhausted: {}, queue uncertain: {}",
                    exhausted, deferred);
        } catch (Exception e) {
            log.warn("[PendingExecutionRequeuer] PENDING 복구 실패 — 다음 주기 재시도", e);
        }
    }
}
