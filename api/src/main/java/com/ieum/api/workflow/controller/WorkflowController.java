package com.ieum.api.workflow.controller;

import com.ieum.api.workflow.dto.CreateWorkflowRequest;
import com.ieum.api.workflow.dto.ExecuteWorkflowRequest;
import com.ieum.api.workflow.dto.UpdateWorkflowRequest;
import com.ieum.api.workflow.dto.WorkflowExecutionLogResponse;
import com.ieum.api.workflow.dto.WorkflowExecutionResponse;
import com.ieum.api.workflow.dto.WorkflowResponse;
import com.ieum.api.workflow.service.ExecutionRetryService;
import com.ieum.api.workflow.service.WorkflowService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import com.ieum.common.dto.PageResponse;
import com.ieum.workflowcore.domain.enums.ExecutionStatus;
import com.ieum.workflowcore.engine.event.ExecutionEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
@Validated
public class WorkflowController implements WorkflowControllerDocs {

    private final WorkflowService workflowService;
    private final ExecutionRetryService executionRetryService;

    @PostMapping
    public ResponseEntity<ApiResponse<WorkflowResponse>> create(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid CreateWorkflowRequest request) {

        WorkflowResponse response = workflowService.createWorkflow(userDetails.getId(), request);
        return ResponseEntity.status(201).body(ApiResponse.created(response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<WorkflowResponse>>> getList(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {

        PageResponse<WorkflowResponse> response =
            workflowService.getWorkflows(userDetails.getId(), cursor, size);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<WorkflowResponse>> get(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID id) {

        WorkflowResponse response = workflowService.getWorkflow(userDetails.getId(), id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<WorkflowResponse>> update(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID id,
            @RequestBody @Valid UpdateWorkflowRequest request) {

        WorkflowResponse response = workflowService.updateWorkflow(userDetails.getId(), id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID id) {

        workflowService.deleteWorkflow(userDetails.getId(), id);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<WorkflowResponse>> activate(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID id) {

        WorkflowResponse response = workflowService.activateWorkflow(userDetails.getId(), id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<WorkflowResponse>> deactivate(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID id) {

        WorkflowResponse response = workflowService.deactivateWorkflow(userDetails.getId(), id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<ApiResponse<WorkflowExecutionResponse>> execute(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID id,
            @RequestBody(required = false) ExecuteWorkflowRequest request) {

        if (request == null) {
            request = new ExecuteWorkflowRequest();
        }
        WorkflowExecutionResponse response =
            workflowService.executeWorkflow(userDetails.getId(), id, request);
        return ResponseEntity.status(202).body(ApiResponse.ok(response));
    }

    @PostMapping("/executions/{executionId}/retry")
    public ResponseEntity<ApiResponse<WorkflowExecutionResponse>> retryExecution(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID executionId) {

        WorkflowExecutionResponse response =
            executionRetryService.retryExecution(userDetails.getId(), executionId);
        return ResponseEntity.status(202).body(ApiResponse.ok(response));
    }

    @GetMapping("/{id}/executions")
    public ResponseEntity<ApiResponse<PageResponse<WorkflowExecutionResponse>>> getExecutions(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID id,
            @RequestParam(required = false) ExecutionStatus status,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        PageResponse<WorkflowExecutionResponse> response =
            workflowService.getExecutions(userDetails.getId(), id, status, from, to, cursor, size);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{id}/executions/{executionId}/logs")
    public ResponseEntity<ApiResponse<List<WorkflowExecutionLogResponse>>> getExecutionLogs(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID id,
            @PathVariable UUID executionId) {

        List<WorkflowExecutionLogResponse> response =
            workflowService.getExecutionLogs(userDetails.getId(), id, executionId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping(value = "/{id}/executions/{executionId}/events",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ExecutionEvent>> streamExecutionEvents(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID id,
            @PathVariable UUID executionId) {

        return workflowService.streamExecutionEvents(userDetails.getId(), id, executionId);
    }
}
