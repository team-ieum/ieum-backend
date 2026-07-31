package com.ieum.api.workflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
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
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.event.ExecutionEvent;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.engine.event.ExecutionEventSnapshot;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;

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
    private final ExecutionEventPublisher executionEventPublisher;
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

    // @Transactional 사용:
    // prepareExecution()은 REQUIRED로 이 트랜잭션에 참여한다.
    // TransactionSynchronizationManager.afterCommit()으로 커밋 완료 후 @Async 런너를 실행하여
    // Race Condition(findById 시점 레코드 미존재) 및 Detached Entity 문제를 방지한다.
    @Transactional
    public WorkflowExecutionResponse executeWorkflow(UUID userId, UUID workflowId,
            ExecuteWorkflowRequest request) {
        Workflow workflow = workflowCrudService.getWorkflowByOwner(userId, workflowId);
        WorkflowVersion latestVersion = workflowCrudService.findLatestVersion(workflowId)
            .orElseThrow(() -> new CustomException(ErrorCode.INVALID_WORKFLOW, "버전이 없는 워크플로우입니다."));

        Map<String, Object> triggerData = request.getTriggerData() != null
            ? request.getTriggerData()
            : Collections.emptyMap();

        WorkflowExecution execution = workflowExecutionService.prepareExecution(
            workflow, latestVersion, TriggerType.MANUAL, triggerData);

        final UUID executionId = execution.getId();
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    workflowExecutionRunner.run(latestVersion, executionId, triggerData);
                }
            }
        );

        return WorkflowExecutionResponse.from(execution);
    }

    public PageResponse<WorkflowExecutionResponse> getExecutions(UUID userId, UUID workflowId,
            ExecutionStatus status, LocalDateTime from, LocalDateTime to, String cursor, int size) {
        workflowCrudService.getWorkflowByOwner(userId, workflowId); // 소유권 검증

        int page = parseCursor(cursor);
        // size + 1개를 받아 초과분 존재로 hasNext를 판단한다 — 별도 count 쿼리가 필요 없다.
        List<WorkflowExecution> executions =
            workflowExecutionService.listExecutions(workflowId, status, from, to, page, size);
        boolean hasNext = executions.size() > size;

        List<WorkflowExecutionResponse> responses = executions.stream()
            .limit(size)
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

    /**
     * 워크플로우 실행 진행 상황을 SSE 스트림으로 반환한다.
     *
     * <p>늦은 구독 보완을 위해 {@code WorkflowExecutionLog} 스냅샷(과거 이벤트)을 먼저 흘린 뒤,
     * 실행이 진행 중이면 라이브 이벤트({@link ExecutionEventPublisher})를 이어 붙인다.
     * 이미 종료된 실행은 스냅샷만 재생하고 스트림을 종료한다.
     */
    public Flux<ServerSentEvent<ExecutionEvent>> streamExecutionEvents(
            UUID userId, UUID workflowId, UUID executionId) {
        workflowCrudService.getWorkflowByOwner(userId, workflowId); // 소유권 검증

        // 공유 sink 생성(subscribe) 전에 executionId가 이 workflowId에 속하는지 먼저 검증한다.
        // 권한 없는 요청이 공유 sink를 생성·오염시키거나, 정리 과정에서 실제 소유자의 라이브
        // 스트림을 끊는 것을 차단하기 위함이다.
        WorkflowExecution execution = workflowExecutionService.getExecution(executionId);
        if (!execution.getWorkflow().getId().equals(workflowId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }

        // 라이브 구독을 스냅샷 조회보다 먼저 등록(sink 생성)해, 스냅샷 조회 직후 발행되는
        // 이벤트가 누락되지 않게 한다. 검증 실패 시에는 구독자가 없을 때만 sink를 정리한다.
        Flux<ExecutionEvent> live = executionEventPublisher.subscribe(executionId);

        ExecutionEventSnapshot snapshot;
        try {
            snapshot = workflowExecutionService.loadEventSnapshot(workflowId, executionId);
        } catch (RuntimeException e) {
            executionEventPublisher.cleanUpIfNoSubscribers(executionId);
            throw e;
        }

        Flux<ExecutionEvent> events;
        if (snapshot.terminal()) {
            // 이미 종료된 실행 — 라이브 불필요. 다른 활성 구독자가 없을 때만 sink를 정리하고
            // 스냅샷만 재생한다(동시 구독 중인 정상 스트림을 끊지 않기 위함).
            executionEventPublisher.cleanUpIfNoSubscribers(executionId);
            events = Flux.fromIterable(snapshot.events());
        } else {
            // 진행 중 — 과거(스냅샷) → 미래(라이브) 연결. 경계 노드가 중복될 수 있으나
            // 프론트가 nodeId+type로 멱등 처리한다(누락보다 중복이 안전).
            events = Flux.fromIterable(snapshot.events()).concatWith(live);
        }

        return events.map(event -> ServerSentEvent.<ExecutionEvent>builder(event)
            .event(event.type().name())
            .build());
    }

    // ------------------------------------------------------------------ PRIVATE

    private WorkflowResponse toResponse(Workflow workflow, WorkflowVersion version) {
        List<NodeDto> nodes = Collections.emptyList();
        List<EdgeDto> edges = Collections.emptyList();
        if (version != null) {
            WorkflowDefinitionDocument definition = workflowCrudService.loadDefinition(version);
            nodes = objectMapper.convertValue(definition.getNodes(), new TypeReference<>() {});
            edges = objectMapper.convertValue(definition.getEdges(), new TypeReference<>() {});
        }
        return WorkflowResponse.from(workflow, version, nodes, edges);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INVALID_WORKFLOW, "워크플로우 데이터 직렬화 실패");
        }
    }

    private int parseCursor(String cursor) {
        // 빈 문자열은 첫 페이지로 본다 — FE가 커서 파라미터를 빈 값으로 초기화해 보내는 경우가 흔하다.
        if (cursor == null || cursor.isBlank()) return 0;
        int page;
        try {
            page = Integer.parseInt(cursor);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.INVALID_CURSOR);
        }
        if (page < 0) {
            throw new CustomException(ErrorCode.INVALID_CURSOR);
        }
        return page;
    }
}
