package com.ieum.api.workflow.service;

import com.ieum.api.alert.ApprovalNotifier;
import com.ieum.common.exception.CustomException;
import com.ieum.workflowcore.deployment.BackgroundOwnership;
import com.ieum.workflowcore.domain.ApprovalGate;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.repository.ApprovalGateRepository;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 승인 게이트 1분 스윕 — 만료 → 첫 알림 → 리마인더. Blue/Green에선 백그라운드 소유 인스턴스만 돌고, 소유권은 만료 건마다·
 * 알림 페이지마다 다시 본다. 알림은 조건부 UPDATE({@code markNotified}·{@code markReminded})로 선점한 것만 보낸다 —
 * 두 인스턴스가 겹쳐도 한 번만 간다.
 *
 * <p>알림 조회는 항상 0번 페이지다. 선점한 행은 결과에서 빠지므로 페이지 번호를 올리면 행을 건너뛴다. 페이지가 덜 찼거나
 * 한 건도 선점하지 못했으면(다른 인스턴스가 먼저 가져갔거나 선점이 계속 실패) 멈춘다.
 *
 * <p>만료가 {@link CustomException}(영구 오류)이면 {@code markAsFailed}로 닫는다(알림 있음). DB 일시 오류 등 그 밖의 예외는
 * 닫지 않고 다음 스윕에 다시 본다.
 *
 * <p>ponytail: 첫 알림이 최대 1분 늦다. 즉시 알림이 필요하면 런타임 pause 뒤 비동기 발신을 더한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalGateSweeper {

    static final int PAGE_SIZE = 100;
    static final String EXPIRE_FAILED = "승인 만료를 처리하지 못했어요";

    @Autowired(required = false)
    private BackgroundOwnership backgroundOwnership;

    private final WorkflowExecutionService workflowExecutionService;
    private final ApprovalGateService approvalGateService;
    private final ApprovalGateRepository approvalGateRepository;
    private final WorkflowCrudService workflowCrudService;
    private final ApprovalNotifier approvalNotifier;

    @Scheduled(fixedDelay = 60_000, initialDelayString = "${ieum.workflow.approval.sweep-initial-delay:PT1M}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        if (!isOwner()) return;
        for (UUID id : workflowExecutionService.findExpiredApprovalExecutionIds(now)) {
            if (!isOwner()) return;
            expireOne(id, now);
        }
        announce(page -> approvalGateRepository.findUnnotified(now, page),
            approvalGateRepository::markNotified, approvalNotifier::notifyRequested, now);
        announce(page -> approvalGateRepository.findDueReminders(now, page),
            approvalGateRepository::markReminded, approvalNotifier::notifyReminder, now);
    }

    private boolean isOwner() {
        return backgroundOwnership == null || backgroundOwnership.isOwner();
    }

    private void expireOne(UUID id, LocalDateTime now) {
        try {
            approvalGateService.expire(id, now);
        } catch (Exception e) {
            String cause = e instanceof CustomException ce ? ce.getErrorCode().name() : e.getClass().getSimpleName();
            log.error("[ApprovalGateSweeper] 만료 실패 — executionId: {}, cause: {}, message: {}",
                id, cause, e.getMessage());
            if (!(e instanceof CustomException)) return;
            try {
                workflowExecutionService.markAsFailed(id, EXPIRE_FAILED, ExecutionStatus.WAITING_APPROVAL);
            } catch (Exception closeFailure) {
                log.error("[ApprovalGateSweeper] 만료 실패 실행 닫기 실패 — executionId: {}, cause: {}",
                    id, closeFailure.getClass().getSimpleName());
            }
        }
    }

    private void announce(Function<Pageable, List<ApprovalGate>> find,
                          BiFunction<UUID, LocalDateTime, Integer> claim,
                          BiConsumer<ApprovalGate, String> send, LocalDateTime now) {
        while (isOwner()) {
            List<ApprovalGate> page = find.apply(PageRequest.of(0, PAGE_SIZE));
            int claimed = 0;
            for (ApprovalGate gate : page) {
                try {
                    String workflowName = workflowCrudService.getWorkflowById(gate.getWorkflowId()).getName();
                    if (claim.apply(gate.getId(), now) != 1) continue;
                    claimed++;
                    send.accept(gate, workflowName);
                } catch (Exception e) {
                    log.error("[ApprovalGateSweeper] 알림 실패 — gateId: {}, cause: {}",
                        gate.getId(), e.getClass().getSimpleName());
                }
            }
            if (page.size() < PAGE_SIZE || claimed == 0) return;
        }
    }
}
