package com.ieum.workflowcore.service;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.repository.WorkflowRepository;
import com.ieum.workflowcore.repository.WorkflowVersionRepository;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.quartz.CronExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워크플로우 CRUD 비즈니스 로직.
 *
 * <p>api 모듈에 대한 의존 없이 순수 도메인 로직만 담당한다.
 * 호출자(api WorkflowService)가 DTO 변환과 페이지 래핑을 담당한다.
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

        WorkflowVersion version = WorkflowVersion.builder()
            .workflow(workflow)
            .version(1)
            .nodesJson(nodesJson)
            .edgesJson(edgesJson)
            .build();
        workflowVersionRepository.save(version);

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
        WorkflowVersion version = WorkflowVersion.builder()
            .workflow(workflow)
            .version(nextVersion)
            .nodesJson(nodesJson)
            .edgesJson(edgesJson)
            .build();
        workflowVersionRepository.save(version);

        log.info("[WorkflowCrudService] 워크플로우 업데이트 — workflowId: {}, version: {}", workflowId, nextVersion);
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
        workflowVersionRepository.deleteByWorkflow(workflow);
        workflowRepository.delete(workflow);

        log.info("[WorkflowCrudService] 워크플로우 삭제 — workflowId: {}", workflowId);
    }

    @Transactional
    public Workflow activateWorkflow(UUID userId, UUID workflowId) {
        Workflow workflow = getWorkflowByOwner(userId, workflowId);
        workflow.activate();
        log.info("[WorkflowCrudService] 워크플로우 활성화 — workflowId: {}", workflowId);
        return workflow;
    }

    @Transactional
    public Workflow deactivateWorkflow(UUID userId, UUID workflowId) {
        Workflow workflow = getWorkflowByOwner(userId, workflowId);
        workflow.deactivate();
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
     * 스케줄 Job에서 사용. 비활성화된 경우 null 반환 (소유권 검증 불필요).
     */
    public Workflow findActiveById(UUID workflowId) {
        return workflowRepository.findById(workflowId)
            .filter(Workflow::isActive)
            .orElse(null);
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

    // ------------------------------------------------------------------ VALIDATE

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
