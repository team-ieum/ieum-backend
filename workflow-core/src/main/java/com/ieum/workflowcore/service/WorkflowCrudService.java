package com.ieum.workflowcore.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.document.WorkflowDefinitionRepository;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.repository.WorkflowRepository;
import com.ieum.workflowcore.repository.WorkflowVersionRepository;
import com.ieum.workflowcore.scheduler.WorkflowScheduler;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronExpression;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 워크플로우 CRUD 비즈니스 로직.
 *
 * <p>api 모듈에 대한 의존 없이 순수 도메인 로직만 담당한다.
 * 호출자(api WorkflowService)가 DTO 변환과 페이지 래핑을 담당한다.
 *
 * <p>SCHEDULE 트리거 워크플로우는 CRUD/활성화/비활성화 시 {@link WorkflowScheduler}를 통해
 * Quartz Job을 자동으로 동기화한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowCrudService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final WorkflowExecutionLogRepository workflowExecutionLogRepository;
    private final WorkflowScheduler workflowScheduler;
    private final WorkflowDefinitionRepository definitionRepository;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ WRITE

    @Transactional
    public WorkflowVersion createWorkflow(UUID userId, String name, String description,
            String nodesJson, String edgesJson, TriggerType triggerType, String cronExpression) {
        validateScheduleConfig(triggerType, cronExpression);

        Workflow workflow = Workflow.builder()
            .userId(userId)
            .name(name)
            .description(description)
            .isActive(true)
            .triggerType(triggerType)
            .cronExpression(cronExpression)
            .build();
        workflowRepository.save(workflow);

        WorkflowVersion version = saveVersionWithCompensation(workflow, 1, nodesJson, edgesJson);

        // DB 커밋 후 Quartz Job 등록 — 트랜잭션 롤백 시 Job이 고아로 남는 것을 방지
        if (triggerType == TriggerType.SCHEDULE) {
            final UUID scheduledWorkflowId = workflow.getId();
            afterCommit(() -> workflowScheduler.registerJob(scheduledWorkflowId, cronExpression));
        }

        log.info("[WorkflowCrudService] 워크플로우 생성 — workflowId: {}, version: 1, triggerType: {}",
            workflow.getId(), workflow.getTriggerType());
        return version;
    }

    @Transactional
    public WorkflowVersion updateWorkflow(UUID userId, UUID workflowId, String name,
            String description, String nodesJson, String edgesJson,
            TriggerType triggerType, String cronExpression) {
        validateScheduleConfig(triggerType, cronExpression);

        Workflow workflow = getWorkflowByOwner(userId, workflowId);
        workflow.update(name, description);
        workflow.updateSchedule(triggerType, cronExpression);

        int nextVersion = workflowVersionRepository.findMaxVersionByWorkflowId(workflowId) + 1;
        WorkflowVersion version = saveVersionWithCompensation(workflow, nextVersion, nodesJson, edgesJson);

        // DB 커밋 후 Job 동기화 — 트랜잭션 롤백 시 Quartz 상태가 DB와 불일치하는 것을 방지
        final Workflow updatedWorkflow = workflow;
        afterCommit(() -> syncScheduleJob(updatedWorkflow));

        log.info("[WorkflowCrudService] 워크플로우 업데이트 — workflowId: {}, version: {}, triggerType: {}",
            workflowId, nextVersion, triggerType);
        return version;
    }

    @Transactional
    public WorkflowVersion saveAgentVersion(UUID workflowId, String nodesJson, String edgesJson) {
        Workflow workflow = workflowRepository.findById(workflowId)
            .orElseThrow(() -> new CustomException(ErrorCode.WORKFLOW_NOT_FOUND));

        int nextVersion = workflowVersionRepository.findMaxVersionByWorkflowId(workflowId) + 1;
        WorkflowVersion version = saveVersionWithCompensation(workflow, nextVersion, nodesJson, edgesJson);

        log.info("[WorkflowCrudService] AI 생성 버전 저장 — workflowId: {}, version: {}",
            workflowId, nextVersion);
        return version;
    }

    @Transactional
    public void deleteWorkflow(UUID userId, UUID workflowId) {
        Workflow workflow = getWorkflowByOwner(userId, workflowId);

        List<UUID> executionIds = workflowExecutionRepository.findByWorkflow(workflow)
            .stream().map(WorkflowExecution::getId).toList();
        if (!executionIds.isEmpty()) {
            workflowExecutionLogRepository.deleteByExecutionIdIn(executionIds);
        }
        workflowExecutionRepository.deleteByWorkflow(workflow);

        // Collect MongoDB document IDs before deleting PG rows (reference will be gone after)
        List<String> mongoIds = workflowVersionRepository.findByWorkflowId(workflow.getId())
            .stream()
            .map(WorkflowVersion::getMongoDefinitionId)
            .filter(id -> id != null && !id.isBlank())
            .toList();

        workflowVersionRepository.deleteByWorkflow(workflow);
        workflowRepository.delete(workflow);

        // Both MongoDB cleanup and Quartz job deletion run after PG transaction commits
        afterCommit(() -> {
            mongoIds.forEach(id -> {
                try {
                    definitionRepository.deleteById(id);
                } catch (Exception e) {
                    log.warn("[WorkflowCrudService] MongoDB doc 삭제 실패 — id: {}", id, e);
                }
            });
            workflowScheduler.deleteJob(workflowId);
        });

        log.info("[WorkflowCrudService] 워크플로우 삭제 — workflowId: {}", workflowId);
    }

    @Transactional
    public Workflow activateWorkflow(UUID userId, UUID workflowId) {
        Workflow workflow = getWorkflowByOwner(userId, workflowId);
        workflow.activate();
        final Workflow activatedWorkflow = workflow;
        afterCommit(() -> syncScheduleJob(activatedWorkflow));
        log.info("[WorkflowCrudService] 워크플로우 활성화 — workflowId: {}", workflowId);
        return workflow;
    }

    @Transactional
    public Workflow deactivateWorkflow(UUID userId, UUID workflowId) {
        Workflow workflow = getWorkflowByOwner(userId, workflowId);
        workflow.deactivate();
        final Workflow deactivatedWorkflow = workflow;
        afterCommit(() -> syncScheduleJob(deactivatedWorkflow));
        log.info("[WorkflowCrudService] 워크플로우 비활성화 — workflowId: {}", workflowId);
        return workflow;
    }

    // ------------------------------------------------------------------ READ

    public List<Workflow> listWorkflows(UUID userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return workflowRepository.findByUserId(userId, pageable);
    }

    public boolean hasNextWorkflows(UUID userId, int page, int size) {
        Pageable pageable = PageRequest.of(page + 1, 1, Sort.by("createdAt").descending());
        return !workflowRepository.findByUserId(userId, pageable).isEmpty();
    }

    public Workflow getWorkflowByOwner(UUID userId, UUID workflowId) {
        return workflowRepository.findByIdAndUserId(workflowId, userId)
            .orElseThrow(() -> new CustomException(ErrorCode.WORKFLOW_NOT_FOUND));
    }

    /**
     * Webhook 트리거에서 사용. 소유권 검증 없이 ID로만 조회 (없으면 예외).
     */
    public Workflow getWorkflowById(UUID workflowId) {
        return workflowRepository.findById(workflowId)
            .orElseThrow(() -> new CustomException(ErrorCode.WORKFLOW_NOT_FOUND));
    }

    /**
     * 스케줄 Job에서 사용. 비활성화된 경우 null 반환 (소유권 검증 불필요).
     */
    public Workflow findActiveById(UUID workflowId) {
        return workflowRepository.findById(workflowId)
            .filter(Workflow::isActive)
            .orElse(null);
    }

    /**
     * 서버 재시작 시 RAMJobStore 복구용 — 활성 SCHEDULE 워크플로우 전체 조회.
     */
    public List<Workflow> findActiveScheduleWorkflows() {
        return workflowRepository.findByTriggerTypeAndIsActive(TriggerType.SCHEDULE, true);
    }

    public Optional<WorkflowVersion> findLatestVersion(UUID workflowId) {
        return workflowVersionRepository.findFirstByWorkflowIdOrderByVersionDesc(workflowId);
    }

    /**
     * 여러 워크플로우 ID의 최신 버전을 workflowId → WorkflowVersion 맵으로 반환한다 (N+1 방지).
     */
    public Map<UUID, WorkflowVersion> getLatestVersionMap(List<UUID> workflowIds) {
        if (workflowIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return workflowVersionRepository.findLatestByWorkflowIds(workflowIds).stream()
            .collect(Collectors.toMap(wv -> wv.getWorkflow().getId(), Function.identity()));
    }

    /**
     * Loads the WorkflowDefinitionDocument from MongoDB for the given WorkflowVersion.
     *
     * @param version WorkflowVersion whose mongoDefinitionId references the MongoDB document
     * @return WorkflowDefinitionDocument containing nodes and edges as List<Map>
     * @throws CustomException WORKFLOW_NOT_FOUND if the document does not exist
     */
    public WorkflowDefinitionDocument loadDefinition(WorkflowVersion version) {
        String mongoId = version.getMongoDefinitionId();
        return definitionRepository.findById(mongoId)
            .orElseThrow(() -> {
                log.error("[WorkflowCrudService] MongoDB 정의 누락 — versionId: {}, mongoId: {}",
                    version.getId(), mongoId);
                return new CustomException(ErrorCode.WORKFLOW_DEFINITION_NOT_FOUND);
            });
    }

    // ------------------------------------------------------------------ PRIVATE

    /**
     * Saves a WorkflowDefinitionDocument to MongoDB and a WorkflowVersion to PostgreSQL
     * with a compensating transaction: if the PG save fails, the MongoDB document is deleted.
     */
    private WorkflowVersion saveVersionWithCompensation(
            Workflow workflow, int versionNumber, String nodesJson, String edgesJson) {
        UUID preGeneratedVersionId = UUID.randomUUID();
        WorkflowDefinitionDocument savedDoc = definitionRepository.save(
            buildDefinitionDocument(preGeneratedVersionId.toString(), nodesJson, edgesJson));

        try {
            WorkflowVersion version = WorkflowVersion.builder()
                .id(preGeneratedVersionId)
                .workflow(workflow)
                .version(versionNumber)
                .mongoDefinitionId(savedDoc.getId())
                .build();
            return workflowVersionRepository.save(version);
        } catch (Exception e) {
            try {
                definitionRepository.deleteById(savedDoc.getId());
            } catch (Exception ignore) {
                log.warn("[WorkflowCrudService] MongoDB 보상 삭제 실패 — mongoId: {}",
                    savedDoc.getId(), ignore);
            }
            throw e;
        }
    }

    private WorkflowDefinitionDocument buildDefinitionDocument(
            String workflowVersionId, String nodesJson, String edgesJson) {
        try {
            List<Map<String, Object>> nodes =
                objectMapper.readValue(nodesJson, new TypeReference<>() {});
            List<Map<String, Object>> edges =
                objectMapper.readValue(edgesJson, new TypeReference<>() {});
            return WorkflowDefinitionDocument.builder()
                .workflowVersionId(workflowVersionId)
                .nodes(nodes)
                .edges(edges)
                .createdAt(LocalDateTime.now())
                .build();
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INVALID_WORKFLOW,
                "워크플로우 JSON 파싱 실패: " + e.getMessage());
        }
    }

    /**
     * 현재 트랜잭션 커밋 이후에 action을 실행한다.
     * Quartz Job 조작을 DB 커밋 이후로 미뤄 트랜잭션 롤백 시 상태 불일치를 방지한다.
     */
    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * 워크플로우의 현재 상태(triggerType + isActive)에 따라 Quartz Job을 동기화한다.
     * <ul>
     *   <li>SCHEDULE + 활성 → registerJob (신규 or Cron 갱신)
     *   <li>그 외 → deleteJob (MANUAL로 변경되거나 비활성인 경우)
     * </ul>
     */
    private void syncScheduleJob(Workflow workflow) {
        if (workflow.getTriggerType() == TriggerType.SCHEDULE && workflow.isActive()) {
            workflowScheduler.registerJob(workflow.getId(), workflow.getCronExpression());
        } else {
            workflowScheduler.deleteJob(workflow.getId());
        }
    }

    /**
     * SCHEDULE 트리거일 때 cronExpression 필수 및 Quartz 형식 유효성 검사.
     */
    private void validateScheduleConfig(TriggerType triggerType, String cronExpression) {
        if (triggerType != TriggerType.SCHEDULE) return;

        if (cronExpression == null || cronExpression.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_CRON_EXPRESSION,
                "SCHEDULE 트리거에는 cronExpression이 필요합니다.");
        }
        if (!CronExpression.isValidExpression(cronExpression)) {
            throw new CustomException(ErrorCode.INVALID_CRON_EXPRESSION,
                "올바르지 않은 Quartz Cron 표현식입니다: " + cronExpression);
        }
    }
}
