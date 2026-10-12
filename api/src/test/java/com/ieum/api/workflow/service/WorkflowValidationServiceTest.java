package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.engine.validation.WorkflowValidationClient;
import com.ieum.workflowcore.engine.validation.WorkflowValidationException;
import com.ieum.workflowcore.engine.validation.WorkflowValidationResult;
import com.ieum.workflowcore.service.WorkflowCrudService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 최신 저장본 검증 — 소유권·버전 확인이 agent 호출보다 먼저고, 실행 경로만 fail-open이다. */
class WorkflowValidationServiceTest {

    private final WorkflowCrudService crudService = mock(WorkflowCrudService.class);
    private final WorkflowValidationClient client = mock(WorkflowValidationClient.class);
    private final WorkflowValidationService service = new WorkflowValidationService(crudService, client);
    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final List<Map<String, Object>> nodes = List.of(Map.of("id", "t", "type", "TRIGGER"));
    private final List<Map<String, Object>> edges = List.of();
    private final WorkflowValidationResult.ValidationError error =
        new WorkflowValidationResult.ValidationError("a", "config.mappings", "REQUIRED", "'매핑'을 입력하세요.");

    @BeforeEach
    void setUp() {
        WorkflowVersion version = mock(WorkflowVersion.class);
        when(crudService.findLatestVersion(workflowId)).thenReturn(Optional.of(version));
        when(crudService.loadDefinition(version)).thenReturn(
            WorkflowDefinitionDocument.builder().nodes(nodes).edges(edges).build());
    }

    @Test
    @DisplayName("validate: 최신 정의를 agent에 넘기고 결과를 그대로 돌려준다")
    void validate_returnsAgentResult() {
        WorkflowValidationResult result = new WorkflowValidationResult(false, List.of(error));
        when(client.validate(nodes, edges)).thenReturn(Optional.of(result));

        assertThat(service.validate(userId, workflowId)).isEqualTo(result);
        verify(crudService).getWorkflowByOwner(userId, workflowId);
    }

    @Test
    @DisplayName("validate: agent에 못 닿으면 502 PROVIDER_ERROR(검증 결과를 지어내지 않는다)")
    void validate_agentUnavailable_isProviderError() {
        when(client.validate(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate(userId, workflowId))
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.PROVIDER_ERROR);
    }

    @Test
    @DisplayName("남의 워크플로우 → 404, agent를 부르지 않는다")
    void notOwner_doesNotCallAgent() {
        when(crudService.getWorkflowByOwner(userId, workflowId))
            .thenThrow(new CustomException(ErrorCode.WORKFLOW_NOT_FOUND));

        assertThatThrownBy(() -> service.validate(userId, workflowId))
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.WORKFLOW_NOT_FOUND);
        assertThatThrownBy(() -> service.assertExecutable(userId, workflowId))
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.WORKFLOW_NOT_FOUND);
        verify(client, never()).validate(any(), any());
    }

    @Test
    @DisplayName("버전 없는 워크플로우 → 400 INVALID_WORKFLOW, agent를 부르지 않는다")
    void noVersion_isInvalidWorkflow() {
        when(crudService.findLatestVersion(workflowId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate(userId, workflowId))
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.INVALID_WORKFLOW);
        verify(client, never()).validate(any(), any());
    }

    @Test
    @DisplayName("assertExecutable: errors 있으면 WorkflowValidationException(목록 보존)")
    void assertExecutable_errors_throw() {
        when(client.validate(nodes, edges)).thenReturn(Optional.of(new WorkflowValidationResult(false, List.of(error))));

        assertThatThrownBy(() -> service.assertExecutable(userId, workflowId))
            .isInstanceOf(WorkflowValidationException.class)
            .hasMessage("검증 실패 1건: '매핑'을 입력하세요.")
            .extracting(e -> ((WorkflowValidationException) e).getErrors())
            .isEqualTo(List.of(error));
    }

    @Test
    @DisplayName("assertExecutable: agent 장애·타임아웃이면 통과(fail-open)")
    void assertExecutable_agentUnavailable_passes() {
        when(client.validate(any(), any())).thenReturn(Optional.empty());

        assertThatCode(() -> service.assertExecutable(userId, workflowId)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertExecutable: valid=false여도 errors가 비면 통과")
    void assertExecutable_noErrors_passes() {
        when(client.validate(any(), any())).thenReturn(Optional.of(new WorkflowValidationResult(false, List.of())));

        assertThatCode(() -> service.assertExecutable(userId, workflowId)).doesNotThrowAnyException();
    }
}
