package com.ieum.api.workflow.queue;

import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import java.time.Duration;
import java.time.LocalDateTime;
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
    @Value("${ieum.workflow.queue.pending-requeue-grace:PT1M}")
    private Duration grace = Duration.ofMinutes(1);
    private int page;
    private static final int PAGE_SIZE = 100;

    @Scheduled(fixedDelayString = "${ieum.workflow.queue.pending-requeue-interval:PT20S}")
    public synchronized void recoverPending() {
        try {
            var ids = executions.findPendingIdsBefore(LocalDateTime.now().minus(grace),
                PageRequest.of(page, PAGE_SIZE));
            // Continue past blocked old rows rather than fetching the first page forever. Changing
            // statuses can move rows between pages; the next complete pass revisits anything skipped.
            page = ids.size() == PAGE_SIZE ? page + 1 : 0;
            ids.forEach(queue::enqueue);
        } catch (Exception e) {
            log.warn("[PendingExecutionRequeuer] PENDING 복구 실패 — 다음 주기 재시도", e);
        }
    }
}
