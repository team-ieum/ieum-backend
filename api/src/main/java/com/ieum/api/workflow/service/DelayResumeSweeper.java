package com.ieum.api.workflow.service;

import com.ieum.common.exception.CustomException;
import com.ieum.workflowcore.deployment.BackgroundOwnership;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 재개 시각이 지난 {@code WAITING_DELAY} 실행을 1분마다 이어간다. Blue/Green에선 백그라운드 소유 인스턴스만 돈다.
 *
 * <p>ponytail: 후보 수 상한이 없고, 같은 실행이 계속 실패하면 매분 다시 시도한다. 폭주하면 LIMIT과 실패 횟수 컬럼을 더한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelayResumeSweeper {

    @Autowired(required = false)
    private BackgroundOwnership backgroundOwnership;

    private final WorkflowExecutionService workflowExecutionService;
    private final ExecutionDelayService executionDelayService;

    @Scheduled(fixedDelay = 60_000, initialDelayString = "${ieum.workflow.delay.sweep-initial-delay:PT1M}")
    public void sweep() {
        if (backgroundOwnership != null && !backgroundOwnership.isOwner()) return;
        for (UUID id : workflowExecutionService.findDueDelayExecutionIds(LocalDateTime.now())) {
            try {
                executionDelayService.resume(id, Instant.now());
            } catch (Exception e) {
                // 원인은 예외 종류·메시지(CustomException이면 ErrorCode)만 — 스택·입력값은 남기지 않는다.
                String cause = e instanceof CustomException ce ? ce.getErrorCode().name() : e.getClass().getSimpleName();
                log.error("[DelayResumeSweeper] 재개 실패 — executionId: {}, cause: {}, message: {}",
                    id, cause, e.getMessage());
            }
        }
    }
}
