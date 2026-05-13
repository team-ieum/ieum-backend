package com.ieum.api.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.dto.CreateWorkflowRequest;
import com.ieum.api.workflow.dto.EdgeDto;
import com.ieum.api.workflow.dto.ExecuteWorkflowRequest;
import com.ieum.api.workflow.dto.NodeDto;
import com.ieum.api.workflow.dto.UpdateWorkflowRequest;
import com.ieum.api.workflow.dto.WorkflowExecutionLogResponse;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.api.workflow.dto.WorkflowResponse;
import com.ieum.common.dto.PageResponse;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowExecution;
import com.ieum.workflowcore.domain.WorkflowExecutionLog;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.repository.WorkflowExecutionLogRepository;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.workflowcore.repository.WorkflowRepository;
import com.ieum.workflowcore.repository.WorkflowVersionRepository;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final WorkflowExecutionLogRepository workflowExecutionLogRepository;
    private final WorkflowExecutionRunner workflowExecutionRunner;
    private final ObjectMapper objectMapper;

    @Transactional
    public WorkflowResponse createWorkflow(UUID userId, CreateWorkflowRequest request) {
        Workflow workflow = Workflow.builder()
            .userId(userId)
            .name(request.getName())
            .description(request.getDescription())
            .isActive(true)
            .build();
        workflowRepository.save(workflow);

        WorkflowVersion version = WorkflowVersion.builder()
            .workflow(workflow)
            .version(1)
            .nodesJson(toJson(request.getNodes()))
            .edgesJson(toJson(request.getEdges()))
            .build();
        workflowVersionRepository.save(version);

        return WorkflowResponse.from(workflow, version, objectMapper);
    }

    public PageResponse<WorkflowResponse> getWorkflows(UUID userId, String cursor, int size) {
        int page = parseCursor(cursor);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        List<Workflow> workflows = workflowRepository.findByUserId(userId, pageable);

        Pageable peekPageable = PageRequest.of(page + 1, 1, Sort.by("createdAt").descending());
        boolean hasNext = !workflowRepository.findByUserId(userId, peekPageable).isEmpty();
        String nextCursor = hasNext ? String.valueOf(page + 1) : null;

        List<UUID> workflowIds = workflows.stream().map(Workflow::getId).toList();
        Map<UUID, WorkflowVersion> latestVersionMap = workflowVersionRepository
            .findLatestByWorkflowIds(workflowIds).stream()
            .collect(Collectors.toMap(wv -> wv.getWorkflow().getId(), Function.identity()));

        List<WorkflowResponse> responses = workflows.stream()
            .map(w -> WorkflowResponse.from(w, latestVersionMap.get(w.getId()), objectMapper))
            .toList();

        return PageResponse.of(responses, hasNext, nextCursor);
    }

    public WorkflowResponse getWorkflow(UUID userId, UUID workflowId) {
        Workflow workflow = findWorkflowByOwner(userId, workflowId);
        WorkflowVersion latestVersion = workflowVersionRepository
            .findFirstByWorkflowIdOrderByVersionDesc(workflowId)
            .orElse(null);
        return WorkflowResponse.from(workflow, latestVersion, objectMapper);
    }

    @Transactional
    public WorkflowResponse updateWorkflow(UUID userId, UUID workflowId,
            UpdateWorkflowRequest request) {
        Workflow workflow = findWorkflowByOwner(userId, workflowId);
        workflow.update(request.getName(), request.getDescription());

        int nextVersion = workflowVersionRepository.findMaxVersionByWorkflowId(workflowId) + 1;
        WorkflowVersion version = WorkflowVersion.builder()
            .workflow(workflow)
            .version(nextVersion)
            .nodesJson(toJson(request.getNodes()))
            .edgesJson(toJson(request.getEdges()))
            .build();
        workflowVersionRepository.save(version);

        return WorkflowResponse.from(workflow, version, objectMapper);
    }

    @Transactional
    public void deleteWorkflow(UUID userId, UUID workflowId) {
        Workflow workflow = findWorkflowByOwner(userId, workflowId);

        List<WorkflowExecution> executions = workflowExecutionRepository.findByWorkflow(workflow);
        for (WorkflowExecution execution : executions) {
            workflowExecutionLogRepository.deleteByExecution(execution);
        }
        workflowExecutionRepository.deleteByWorkflow(workflow);
        workflowVersionRepository.deleteByWorkflow(workflow);
        workflowRepository.delete(workflow);
    }

    @Transactional
    public WorkflowResponse activateWorkflow(UUID userId, UUID workflowId) {
        Workflow workflow = findWorkflowByOwner(userId, workflowId);
        workflow.activate();
        WorkflowVersion latestVersion = workflowVersionRepository
            .findFirstByWorkflowIdOrderByVersionDesc(workflowId).orElse(null);
        return WorkflowResponse.from(workflow, latestVersion, objectMapper);
    }

    @Transactional
    public WorkflowResponse deactivateWorkflow(UUID userId, UUID workflowId) {
        Workflow workflow = findWorkflowByOwner(userId, workflowId);
        workflow.deactivate();
        WorkflowVersion latestVersion = workflowVersionRepository
            .findFirstByWorkflowIdOrderByVersionDesc(workflowId).orElse(null);
        return WorkflowResponse.from(workflow, latestVersion, objectMapper);
    }

    @Transactional
    public WorkflowExecutionResponse executeWorkflow(UUID userId, UUID workflowId,
            ExecuteWorkflowRequest request) {
        Workflow workflow = findWorkflowByOwner(userId, workflowId);

        if (!workflow.isActive()) {
            throw new CustomException(ErrorCode.INVALID_WORKFLOW, "비활성화된 워크플로우입니다.");
        }

        WorkflowVersion latestVersion = workflowVersionRepository
            .findFirstByWorkflowIdOrderByVersionDesc(workflowId)
            .orElseThrow(() -> new CustomException(ErrorCode.INVALID_WORKFLOW, "버전이 없는 워크플로우입니다."));

        WorkflowExecution execution = WorkflowExecution.builder()
            .workflow(workflow)
            .workflowVersion(latestVersion)
            .status(ExecutionStatus.PENDING)
            .triggerType(TriggerType.MANUAL)
            .startedAt(LocalDateTime.now())
            .build();
        workflowExecutionRepository.save(execution);

        Map<String, Object> triggerData = request.getTriggerData() != null
            ? request.getTriggerData()
            : Collections.emptyMap();
        workflowExecutionRunner.run(latestVersion, execution, triggerData);

        return WorkflowExecutionResponse.from(execution);
    }

    public PageResponse<WorkflowExecutionResponse> getExecutions(UUID userId, UUID workflowId,
            String cursor, int size) {
        findWorkflowByOwner(userId, workflowId);

        int page = parseCursor(cursor);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        List<WorkflowExecution> executions = workflowExecutionRepository
            .findByWorkflowId(workflowId, pageable);

        Pageable peekPageable = PageRequest.of(page + 1, 1, Sort.by("createdAt").descending());
        boolean hasNext = !workflowExecutionRepository
            .findByWorkflowId(workflowId, peekPageable).isEmpty();
        String nextCursor = hasNext ? String.valueOf(page + 1) : null;

        List<WorkflowExecutionResponse> responses = executions.stream()
            .map(WorkflowExecutionResponse::from)
            .toList();

        return PageResponse.of(responses, hasNext, nextCursor);
    }

    public List<WorkflowExecutionLogResponse> getExecutionLogs(UUID userId, UUID workflowId,
            UUID executionId) {
        findWorkflowByOwner(userId, workflowId);

        WorkflowExecution execution = workflowExecutionRepository.findById(executionId)
            .orElseThrow(() -> new CustomException(ErrorCode.EXECUTION_NOT_FOUND));

        if (!execution.getWorkflow().getId().equals(workflowId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }

        List<WorkflowExecutionLog> logs = workflowExecutionLogRepository
            .findByExecutionIdOrderByCreatedAtAsc(executionId);

        return logs.stream()
            .map(WorkflowExecutionLogResponse::from)
            .toList();
    }

    private Workflow findWorkflowByOwner(UUID userId, UUID workflowId) {
        return workflowRepository.findByIdAndUserId(workflowId, userId)
            .orElseThrow(() -> new CustomException(ErrorCode.WORKFLOW_NOT_FOUND));
    }

    private int parseCursor(String cursor) {
        if (cursor == null) return 0;
        try {
            return Integer.parseInt(cursor);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.INVALID_CURSOR);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INVALID_WORKFLOW, "워크플로우 데이터 직렬화 실패");
        }
    }
}
