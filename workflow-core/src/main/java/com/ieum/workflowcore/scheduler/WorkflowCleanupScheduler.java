package com.ieum.workflowcore.scheduler;

import com.ieum.workflowcore.config.StuckExecutionProperties;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.engine.event.ExecutionEvent;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 워크플로우 주기 정리 스케줄러 — 고아 빈 워크플로우 삭제와 고립 {@code RUNNING} 실행 확정.
 *
 * <p>고아 빈 워크플로우: 생성된 지 30분이 지났고 AI가 아직 워크플로우를 설계하지 않은 경우
 * (maxVersion == 1), MongoDB에서 nodes가 비어있음을 확인한 후 완전 삭제(Hard Delete)한다.
 * 2단계 확인 이유는 nodes/edges가 MongoDB에 있어 PostgreSQL 단일 쿼리로 빈 상태를 확인할 수 없어서다.
 *
 * <p>고립 실행: {@link #failStuckRunningExecutions()} 참조.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowCleanupScheduler {

    private static final long ORPHAN_THRESHOLD_MINUTES = 30;

    private final WorkflowCrudService workflowCrudService;
    private final WorkflowExecutionService workflowExecutionService;
    private final ExecutionEventPublisher executionEventPublisher;
    private final StuckExecutionProperties stuckExecutionProperties;

    @Scheduled(cron = "0 */10 * * * *")
    public void cleanupOrphanWorkflows() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(ORPHAN_THRESHOLD_MINUTES);
        List<UUID> candidates = workflowCrudService.findOrphanCandidates(threshold);

        if (candidates.isEmpty()) {
            return;
        }

        log.info("[WorkflowCleanupScheduler] 고아 워크플로우 정리 시작 — 후보 수: {}", candidates.size());

        int deleted = 0;
        for (UUID workflowId : candidates) {
            try {
                boolean isBlank = workflowCrudService.findLatestVersion(workflowId)
                    .map(version -> {
                        WorkflowDefinitionDocument doc = workflowCrudService.loadDefinition(version);
                        return doc.getNodes() == null || doc.getNodes().isEmpty();
                    })
                    .orElse(true);

                if (isBlank) {
                    workflowCrudService.deleteWorkflowById(workflowId);
                    deleted++;
                }
            } catch (Exception e) {
                log.error("[WorkflowCleanupScheduler] 삭제 중 오류 — workflowId: {}", workflowId, e);
            }
        }

        log.info("[WorkflowCleanupScheduler] 정리 완료 — 삭제: {}건 / 후보: {}건", deleted, candidates.size());
    }

    /**
     * 임계를 넘겨 {@code RUNNING}에 고정된 실행을 {@code FAILED}로 확정한다.
     *
     * <p>실행 도중 프로세스가 죽으면 런타임이 종료 처리를 못 해 실행이 영원히 {@code RUNNING}으로
     * 남는다. 그 실행은 재처리 API가 {@code FAILED}만 받으므로 다시 돌릴 수 없고, 재처리로 생긴
     * 실행이 이렇게 굳으면 원본까지 "재처리 진행 중"으로 판정되어 <b>영구히 막힌다.</b>
     * 이 sweeper가 그 상태를 풀어 주는 유일한 경로다.
     *
     * <p>확정은 {@link WorkflowExecutionService#markAsFailed}에 맡긴다 — 런타임 밖에서 실패를
     * 확정하는 기존 경로이고, 종료 상태 가드·알림 발신·커밋 이후 발신을 이미 갖췄다.
     * 런타임의 {@code finalizeFailure}는 실행 중인 인스턴스의 상태를 쥐고 도는 private 경로라
     * 여기서 쓸 수 없고, 쓸 이유도 없다. {@code retryExhausted}는 markAsFailed가 false로 둔다 —
     * 재시도를 소진한 실패가 아니라 재시도 판정 자체가 이뤄지지 못한 실패다.
     *
     * <p>SSE 처리는 {@code markAsFailed}가 실제로 전이시켰을 때만 한다. 조회와 개별 처리 사이에
     * 실행이 스스로 SUCCESS로 끝날 수 있고(항목당 알림 발신이 동기라 그 간격이 수십 초까지 벌어진다),
     * 그때 무조건 발행하면 DB는 SUCCESS인데 구독자에게만 FAILED를 통보하게 된다.
     *
     * <p>전이한 경우엔 런타임의 다른 종료 경로와 같이 {@code EXECUTION_COMPLETED(FAILED)}를
     * 먼저 발행한 뒤 닫는다. 라이브 구독자에게 종료를 알리는 신호는 그 이벤트 하나뿐이라,
     * {@code complete()}만 하면 프론트는 이유 없이 끊긴 스트림만 보고 실행 중 표시가 남는다.
     * 구독자는 프로세스가 죽은 뒤에도 생긴다 — {@code RUNNING}인 실행 상세를 열면 스냅샷은
     * 비terminal이라 종료 이벤트를 담지 않고 그대로 라이브 스트림에 매달린다.
     * {@code publish()}가 Sink를 새로 만들더라도 곧바로 {@code complete()}가 제거하므로 새지 않는다.
     */
    @Scheduled(cron = "0 */10 * * * *")
    public void failStuckRunningExecutions() {
        Duration threshold = stuckExecutionProperties.getThreshold();
        List<UUID> stuckIds = workflowExecutionService.findStuckRunningExecutionIds(
            LocalDateTime.now().minus(threshold));

        if (stuckIds.isEmpty()) {
            return;
        }

        log.warn("[WorkflowCleanupScheduler] 고립 RUNNING 실행 감지 — {}건, 임계: {}",
            stuckIds.size(), threshold);

        // 사용자에게 그대로 노출되는 문구다(대시보드 에러 목록) — Duration.toString(PT2H)이 아니라 분으로 쓴다.
        String reason = "실행 중이던 프로세스가 응답을 멈춰 시스템이 실패로 확정했습니다"
            + " (프로세스 이상 종료 추정 — " + threshold.toMinutes() + "분 넘게 진행 상태 유지).";

        for (UUID executionId : stuckIds) {
            try {
                if (!workflowExecutionService.markAsFailed(executionId, reason)) {
                    // 조회와 이 시점 사이에 실행이 스스로 끝났다 — 종료 이벤트는 그쪽이 이미 흘렸다.
                    log.info("[WorkflowCleanupScheduler] 고립 후보가 이미 종료됨, 건너뜀 — executionId: {}",
                        executionId);
                    continue;
                }
                executionEventPublisher.publish(executionId,
                    ExecutionEvent.executionCompleted(ExecutionStatus.FAILED));
                executionEventPublisher.complete(executionId);
                log.warn("[WorkflowCleanupScheduler] 고립 실행 FAILED 확정 — executionId: {}",
                    executionId);
            } catch (Exception e) {
                log.error("[WorkflowCleanupScheduler] 고립 실행 확정 실패 — executionId: {}",
                    executionId, e);
            }
        }
    }
}
