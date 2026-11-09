package com.ieum.api.workflow.service;

import com.ieum.api.alert.ApprovalNotifier;
import com.ieum.api.alert.DiscordWebhookSender.SendResult;
import com.ieum.common.exception.CustomException;
import com.ieum.workflowcore.deployment.BackgroundOwnership;
import com.ieum.workflowcore.domain.ApprovalGate;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.repository.ApprovalGateRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository.ExpiredApproval;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * 승인 게이트 1분 스윕 — 만료 → 첫 알림 → 리마인더. Blue/Green에선 백그라운드 소유 인스턴스만 돌고, 소유권은 만료 건마다·
 * 알림 페이지마다 다시 본다. 알림은 조건부 UPDATE({@code markNotified}·{@code markReminded})로 선점한 것만 보낸다 —
 * 두 인스턴스가 겹쳐도 한 번만 간다.
 *
 * <p>알림 조회는 건너뛴 행 수만큼만 offset을 올린다 — 선점한 행은 결과에서 빠지므로 페이지 번호를 올리면 행을 건너뛴다
 * ({@link #announce} 참고). 페이지가 덜 찼거나 한 건도 선점·건너뛰지 않았으면 멈춘다.
 *
 * <p>만료가 {@link CustomException}(영구 오류)으로 실패하거나, 실행 기한이 1일 넘게 지났는데 DB 일시 오류
 * ({@code DataAccessException}·{@code TransactionException} 계열)가 아닌 예외로 실패하면 {@code markAsFailed}로 닫는다
 * (알림 있음, {@code DelayResumeSweeper}와 같은 규칙). 그 밖은 닫지 않고 다음 스윕에 다시 본다.
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
        // 선점 시각을 그대로 되돌리기 조건으로 쓴다 — DB(마이크로초) 정밀도에 맞춰 둔다.
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        if (!isOwner()) return;
        for (ExpiredApproval expired : workflowExecutionService.findExpiredApprovals(now)) {
            if (!isOwner()) return;
            expireOne(expired, now);
        }
        Set<UUID> limitedWebhooks = new HashSet<>();
        boolean unreachable = announce(page -> approvalGateRepository.findUnnotified(now, page),
            approvalGateRepository::markNotified, approvalGateRepository::unmarkNotified,
            approvalNotifier::notifyRequested, now, limitedWebhooks);
        if (unreachable) return;
        announce(page -> approvalGateRepository.findDueReminders(now, page), approvalGateRepository::markReminded,
            approvalGateRepository::unmarkReminded, approvalNotifier::notifyReminder, now, limitedWebhooks);
    }

    private boolean isOwner() {
        return backgroundOwnership == null || backgroundOwnership.isOwner();
    }

    private void expireOne(ExpiredApproval expired, LocalDateTime now) {
        UUID id = expired.getId();
        try {
            approvalGateService.expire(id, now);
        } catch (Exception e) {
            String cause = e instanceof CustomException ce ? ce.getErrorCode().name() : e.getClass().getSimpleName();
            log.error("[ApprovalGateSweeper] 만료 실패 — executionId: {}, cause: {}, message: {}",
                id, cause, e.getMessage());
            boolean permanent = e instanceof CustomException;
            // DB 일시 오류는 1일 넘게 밀렸어도 닫지 않는다 — 장애 뒤 첫 재시도에서 영구 실패로 굳히지 않게 다음 스윕에 다시 본다.
            boolean transientFailure = e instanceof DataAccessException || e instanceof TransactionException;
            boolean overdue = expired.getApprovalDeadline() != null
                && expired.getApprovalDeadline().isBefore(now.minusDays(1));
            if (!permanent && !(overdue && !transientFailure)) return;
            try {
                workflowExecutionService.markAsFailed(id, EXPIRE_FAILED, ExecutionStatus.WAITING_APPROVAL);
            } catch (Exception closeFailure) {
                log.error("[ApprovalGateSweeper] 만료 실패 실행 닫기 실패 — executionId: {}, cause: {}",
                    id, closeFailure.getClass().getSimpleName());
            }
        }
    }

    /**
     * 일시 실패(RETRYABLE)면 선점을 되돌려 다음 스윕에 다시 잡히게 하고, 그 웹훅은 이 스윕에서 더 보내지 않는다
     * (레이트 리밋에 연달아 부딪히지 않게). PERMANENT는 되돌리지 않는다. 연결 단계 실패(UNREACHABLE)면 선점을 되돌리고
     * 이 스윕의 남은 발송(리마인더 포함)을 멈춘다 — Discord가 안 닿으면 웹훅마다 연결 타임아웃을 기다리며 공용 스케줄러
     * 스레드를 붙잡기 때문이다. 읽기 타임아웃은 RETRYABLE이라 다음 분에 다시 보내는데, Discord가 이미 게시했을 수 있어
     * 같은 알림이 두 번 갈 수 있다(감수).
     *
     * <p>페이지는 offset으로 넘긴다 — 키셋은 첫 알림 {@code (created_at, id)}·리마인더 {@code (remind_at, id)}로 조회가
     * 둘 다 바뀌지만, offset은 "결과에 남은 행 수"만 세면 된다. 선점에 성공한 행(SENT·PERMANENT)과 선점이 0인 행
     * (남이 가져갔거나 결정됨)은 결과에서 빠지고, 건너뛴 행(되돌림·막힌 웹훅·예외)은 남으므로 그 수만큼 offset을 올린다.
     * 0번 페이지만 반복하면 건너뛴 행으로 꽉 찬 페이지 뒤의 게이트가 굶는다. 반복마다 offset이 오르거나 행이 빠지므로
     * 끝나고, 둘 다 없으면(전부 선점 0) 멈춘다. 다른 인스턴스가 앞쪽 행을 가져가 몇 행을 건너뛰어도 다음 스윕에 잡힌다.
     *
     * @return 연결 단계 실패로 멈췄으면 true
     */
    private boolean announce(Function<Pageable, List<ApprovalGate>> find,
                             BiFunction<UUID, LocalDateTime, Integer> claim,
                             BiFunction<UUID, LocalDateTime, Integer> unclaim,
                             BiFunction<ApprovalGate, String, SendResult> send, LocalDateTime now,
                             Set<UUID> limitedWebhooks) {
        Set<UUID> released = new HashSet<>();
        long offset = 0;
        while (isOwner()) {
            List<ApprovalGate> page = find.apply(page(offset));
            int skipped = 0;
            int claimed = 0;
            for (ApprovalGate gate : page) {
                if (released.contains(gate.getId()) || limitedWebhooks.contains(gate.getWebhookId())) {
                    skipped++;
                    continue;
                }
                try {
                    String workflowName = workflowCrudService.getWorkflowById(gate.getWorkflowId()).getName();
                    if (claim.apply(gate.getId(), now) != 1) continue;
                    claimed++;
                    SendResult result = send.apply(gate, workflowName);
                    if (result == SendResult.RETRYABLE || result == SendResult.UNREACHABLE) {
                        limitedWebhooks.add(gate.getWebhookId());
                        released.add(gate.getId());
                        unclaim.apply(gate.getId(), now);
                        skipped++;
                    }
                    if (result == SendResult.UNREACHABLE) {
                        log.warn("[ApprovalGateSweeper] Discord에 연결하지 못해 이번 스윕의 남은 알림을 멈춤 — gateId: {}",
                            gate.getId());
                        return true;
                    }
                } catch (Exception e) {
                    skipped++;
                    log.error("[ApprovalGateSweeper] 알림 실패 — gateId: {}, cause: {}",
                        gate.getId(), e.getClass().getSimpleName());
                }
            }
            if (page.size() < PAGE_SIZE || (skipped == 0 && claimed == 0)) return false;
            offset += skipped;
        }
        return false;
    }

    private static Pageable page(long offset) {
        return new PageRequest(0, PAGE_SIZE, Sort.unsorted()) {
            @Override
            public long getOffset() {
                return offset;
            }
        };
    }
}
