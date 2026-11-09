package com.ieum.api.workflow.service;

import com.ieum.common.exception.CustomException;
import com.ieum.workflowcore.deployment.BackgroundOwnership;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository.DueDelay;
import com.ieum.workflowcore.service.DelayQueueService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 재개 시각이 지난 {@code WAITING_DELAY} 실행을 1분마다 이어간다. Blue/Green에선 백그라운드 소유 인스턴스만 돌고,
 * 소유권은 페이지(100건)마다 다시 본다.
 *
 * <p>재개가 {@link CustomException}(복호 실패 등 영구 오류)으로 실패하거나 재개 시각이 1일 넘게 지났으면
 * {@code markAsFailed("재개하지 못했어요")}로 닫는다 — 알림이 가고 재처리할 수 있다. 그 밖의 일시 오류는 다음 스윕에 다시 본다.
 * 스윕 끝에 오래된 큐 슬롯을 정리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelayResumeSweeper {

    static final String RESUME_FAILED = "재개하지 못했어요";
    static final int PAGE_SIZE = 100;
    private static final LocalDateTime FIRST_AT = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final UUID FIRST_ID = new UUID(0L, 0L);

    @Autowired(required = false)
    private BackgroundOwnership backgroundOwnership;

    private final WorkflowExecutionService workflowExecutionService;
    private final ExecutionDelayService executionDelayService;
    private final DelayQueueService delayQueueService;

    @Scheduled(fixedDelay = 60_000, initialDelayString = "${ieum.workflow.delay.sweep-initial-delay:PT1M}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime afterAt = FIRST_AT;
        UUID afterId = FIRST_ID;
        while (true) {
            if (!isOwner()) return;
            List<DueDelay> page = workflowExecutionService.findDueDelayPage(now, afterAt, afterId, PAGE_SIZE);
            for (DueDelay due : page) {
                resumeOne(due, now);
            }
            if (page.size() < PAGE_SIZE) break;
            DueDelay last = page.get(page.size() - 1);
            afterAt = last.getResumeAt();
            afterId = last.getId();
        }
        if (isOwner()) delayQueueService.purgeOldSlots(Instant.now());
    }

    private boolean isOwner() {
        return backgroundOwnership == null || backgroundOwnership.isOwner();
    }

    private void resumeOne(DueDelay due, LocalDateTime now) {
        UUID id = due.getId();
        try {
            executionDelayService.resume(id, Instant.now());
        } catch (Exception e) {
            // 원인은 예외 종류·메시지(CustomException이면 ErrorCode)만 — 스택·입력값은 남기지 않는다.
            String cause = e instanceof CustomException ce ? ce.getErrorCode().name() : e.getClass().getSimpleName();
            log.error("[DelayResumeSweeper] 재개 실패 — executionId: {}, cause: {}, message: {}",
                id, cause, e.getMessage());
            boolean permanent = e instanceof CustomException;
            boolean overdue = due.getResumeAt() != null && due.getResumeAt().isBefore(now.minusDays(1));
            if (permanent || overdue) {
                try {
                    workflowExecutionService.markAsFailed(id, RESUME_FAILED, ExecutionStatus.WAITING_DELAY);
                } catch (Exception closeFailure) {
                    log.error("[DelayResumeSweeper] 재개 실패 실행 닫기 실패 — executionId: {}, cause: {}",
                        id, closeFailure.getClass().getSimpleName());
                }
            }
        }
    }
}
