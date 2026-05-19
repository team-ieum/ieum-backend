package com.ieum.api.workflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
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
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API 레이어 워크플로우 서비스.
 *
 * <p>Controller 요청을 받아 {@link WorkflowCrudService}, {@link WorkflowExecutionService}에
 * 위임하고, 결과를 DTO로 변환하여 반환하는 얇은 레이어다.
 * 비즈니스 로직은 workflow-core 서비스에 위치한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowService {

    private final WorkflowCrudService workflowCrudService;
    private final WorkflowExecutionService workflowExecutionService;
    private final WorkflowExecutionRunner workflowExecutionRunner;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ CRUD

    @Transactional
    public WorkflowResponse createWorkflow(UUID userId, CreateWorkflowRequest request) {
        WorkflowVersion version = workflowCrudService.createWorkflow(
            userId,
            request.getName(),
            request.getDescription(),
            toJson(request.getNodes()),
            toJson(request.getEdges()),
            request.getTriggerType(),
            request.getCronExpression()
        );
        return toResponse(version.getWorkflow(), version);
    }

    public PageResponse<WorkflowResponse> getWorkflows(UUID userId, String cursor, int size) {
        int page = parseCursor(cursor);
        List<Workflow> workflows = workflowCrudService.listWorkflows(userId, page, size);
        boolean hasNext = workflowCrudService.hasNextWorkflows(userId, page, size);

        List<UUID> workflowIds = workflows.stream().map(Workflow::getId).toList();
        Map<UUID, WorkflowVersion> versionMap = workflowCrudService.getLatestVersionMap(workflowIds);

        List<WorkflowResponse> responses = workflows.stream()
            .map(w -> toResponse(w, versionMap.get(w.getId())))
            .toList();

        return PageResponse.of(responses, hasNext, hasNext ? String.valueOf(page + 1) : null);
    }

    public WorkflowResponse getWorkflow(UUID userId, UUID workflowId) {
        Workflow workflow = workflowCrudService.getWorkflowByOwner(userId, workflowId);
        WorkflowVersion latestVersion = workflowCrudService.findLatestVersion(workflowId).orElse(null);
        return toResponse(workflow, latestVersion);
    }

    @Transactional
    public WorkflowResponse updateWorkflow(UUID userId, UUID workflowId,
            UpdateWorkflowRequest request) {
        WorkflowVersion version = workflowCrudService.updateWorkflow(
            userId,
            workflowId,
            request.getName(),
            request.getDescription(),
            toJson(request.getNodes()),
            toJson(request.getEdges()),
            request.getTriggerType(),
            request.getCronExpression()
        );
        return toResponse(version.getWorkflow(), version);
    }

    @Transactional
    public void deleteWorkflow(UUID userId, UUID workflowId) {
        workflowCrudService.deleteWorkflow(userId, workflowId);
    }

    @Transactional
    public WorkflowResponse activateWorkflow(UUID userId, UUID workflowId) {
        Workflow workflow = workflowCrudService.activateWorkflow(userId, workflowId);
        WorkflowVersion latestVersion = workflowCrudService.findLatestVersion(workflowId).orElse(null);
        return toResponse(workflow, latestVersion);
    }

    @Transactional
    public WorkflowResponse deactivateWorkflow(UUID userId, UUID workflowId) {
        Workflow workflow = workflowCrudService.deactivateWorkflow(userId, workflowId);
        WorkflowVersion latestVersion = workflowCrudService.findLatestVersion(workflowId).orElse(null);
        return toResponse(workflow, latestVersion);
    }

    // ------------------------------------------------------------------ EXECUTION

    // @Transactional 미사용 의도:
    // prepareExecution()이 자체 트랜잭션으로 커밋된 뒤 @Async 런너가 실행되어야
    // 비동기 스레드에서 detached entity merge 오류를 방지할 수 있다.
    public WorkflowExecutionResponse executeWorkflow(UUID userId, UUID workflowId,
            ExecuteWorkflowRequest request) {
        Workflow workflow = workflowCrudService.getWorkflowByOwner(userId, workflowId);
        WorkflowVersion latestVersion = workflowCrudService.findLatestVersion(workflowId)
            .orElseThrow(() -> new CustomException(ErrorCode.INVALID_WORKFLOW, "버전이 없는 워크플로우입니다."));

        WorkflowExecution execution = workflowExecutionService.prepareExecution(
            workflow, latestVersion, TriggerType.MANUAL);

        Map<String, Object> triggerData = request.getTriggerData() != null
            ? request.getTriggerData()
            : Collections.emptyMap();
        workflowExecutionRunner.run(latestVersion, execution.getId(), triggerData);

        return WorkflowExecutionResponse.from(execution);
    }

    public PageResponse<WorkflowExecutionResponse> getExecutions(UUID userId, UUID workflowId,
            String cursor, int size) {
        workflowCrudService.getWorkflowByOwner(userId, workflowId); // 소유권 검증

        int page = parseCursor(cursor);
        List<WorkflowExecution> executions = workflowExecutionService.listExecutions(workflowId, page, size);
        boolean hasNext = workflowExecutionService.hasNextExecutions(workflowId, page, size);

        List<WorkflowExecutionResponse> responses = executions.stream()
            .map(WorkflowExecutionResponse::from)
            .toList();

        return PageResponse.of(responses, hasNext, hasNext ? String.valueOf(page + 1) : null);
    }

    public List<WorkflowExecutionLogResponse> getExecutionLogs(UUID userId, UUID workflowId,
            UUID executionId) {
        workflowCrudService.getWorkflowByOwner(userId, workflowId); // 소유권 검증
        return workflowExecutionService.listExecutionLogs(workflowId, executionId).stream()
            .map(WorkflowExecutionLogResponse::from)
            .toList();
    }

    // ------------------------------------------------------------------ PRIVATE

    private WorkflowResponse toResponse(Workflow workflow, WorkflowVersion version) {
        List<NodeDto> nodes = version != null ? parseNodes(version.getNodesJson()) : Collections.emptyList();
        List<EdgeDto> edges = version != null ? parseEdges(version.getEdgesJson()) : Collections.emptyList();
        return WorkflowResponse.from(workflow, version, nodes, edges);
    }

    private List<NodeDto> parseNodes(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[WorkflowService] nodes JSON 파싱 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<EdgeDto> parseEdges(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[WorkflowService] edges JSON 파싱 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INVALID_WORKFLOW, "워크플로우 데이터 직렬화 실패");
        }
    }

    private int parseCursor(String cursor) {
        if (cursor == null) return 0;
        try {
            return Integer.parseInt(cursor);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.INVALID_CURSOR);
        }
    }
}
