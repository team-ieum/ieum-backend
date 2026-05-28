package com.ieum.workflowcore.scheduler;

import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 고아 빈 워크플로우 정리 스케줄러.
 *
 * <p>생성된 지 30분이 지났고 AI가 아직 워크플로우를 설계하지 않은 경우(maxVersion == 1),
 * MongoDB에서 nodes가 비어있음을 확인한 후 완전 삭제(Hard Delete)한다.
 *
 * <p>2단계 확인 이유: nodes/edges는 MongoDB에 저장되어 있어
 * PostgreSQL 단일 쿼리로 빈 상태를 확인할 수 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowCleanupScheduler {

    private static final long ORPHAN_THRESHOLD_MINUTES = 30;

    private final WorkflowCrudService workflowCrudService;

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
}
