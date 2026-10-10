package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.credential.service.CredentialService;
import com.ieum.api.webhookcredential.service.WebhookCredentialService;
import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.dto.CreateWorkflowRequest;
import com.ieum.api.workflow.dto.UpdateWorkflowRequest;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * REST 생성·수정이 TRIGGER 노드의 {@code config.triggerType}·{@code cron}을 워크플로우 최상위 값의 진실원으로
 * 쓰는지 본다. 노드가 선언하면 둘 다 노드 값이 최상위 요청 값을 덮고(노드에 cron이 없으면 null), 선언이 없을 때만
 * 기존 최상위 값·폴백을 쓴다(하위호환). 수정은 직전 정의와 병합한 결과가 기준이다.
 */
class WorkflowTriggerScheduleTest {

    private static final String NODE_CRON = "0 0 10 * * ?";
    private static final String SCHEDULE_TRIGGER =
        "{ \"id\": \"t\", \"type\": \"TRIGGER\", \"label\": \"시작\","
            + " \"config\": { \"triggerType\": \"SCHEDULE\", \"cron\": \"" + NODE_CRON + "\" } }";

    private final WorkflowCrudService workflowCrudService = mock(WorkflowCrudService.class);
    private final WorkflowService workflowService = new WorkflowService(
        workflowCrudService,
        mock(WorkflowExecutionService.class),
        mock(WorkflowExecutionRunner.class),
        mock(ExecutionEventPublisher.class),
        new ObjectMapper(),
        mock(WebhookCredentialService.class),
        mock(CredentialService.class));

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final Workflow currentWorkflow = mock(Workflow.class);

    /** 저장이 성공하는 경로 — 응답 변환까지 가려면 crud 반환값이 필요하다. */
    private void stubSaveSucceeds() {
        Workflow workflow = mock(Workflow.class);
        WorkflowVersion version = mock(WorkflowVersion.class);
        given(version.getWorkflow()).willReturn(workflow);
        given(workflowCrudService.createWorkflow(
            any(), anyString(), any(), anyString(), anyString(), any(), any())).willReturn(version);
        given(workflowCrudService.updateWorkflow(
            any(), any(), anyString(), any(), anyString(), anyString(), any(), any())).willReturn(version);
        given(workflowCrudService.loadDefinition(version)).willReturn(
            WorkflowDefinitionDocument.builder().nodes(List.of()).edges(List.of()).build());
    }

    private void stubStoredWorkflow(TriggerType type, String cron) {
        given(workflowCrudService.getWorkflowByOwner(userId, workflowId)).willReturn(currentWorkflow);
        given(currentWorkflow.getTriggerType()).willReturn(type);
        given(currentWorkflow.getCronExpression()).willReturn(cron);
    }

    /** 저장돼 있는 직전 버전 정의 — 수정 요청이 config를 생략하면 이 config가 되살아난다. */
    private void stubPreviousNodes(List<Map<String, Object>> previousNodes) {
        WorkflowVersion previousVersion = mock(WorkflowVersion.class);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(previousVersion));
        given(workflowCrudService.loadDefinition(previousVersion)).willReturn(
            WorkflowDefinitionDocument.builder().nodes(previousNodes).edges(List.of()).build());
    }

    private CreateWorkflowRequest create(String nodeJson, String extraFields) {
        return read(CreateWorkflowRequest.class, nodeJson, extraFields);
    }

    private UpdateWorkflowRequest update(String nodeJson, String extraFields) {
        return read(UpdateWorkflowRequest.class, nodeJson, extraFields);
    }

    private <T> T read(Class<T> type, String nodeJson, String extraFields) {
        String body = """
            { "name": "스케줄 워크플로우", "nodes": [ %s ], "edges": []%s }""".formatted(nodeJson, extraFields);
        try {
            return jsonMapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Saved(TriggerType triggerType, String cron) {
    }

    private Saved capturedOnCreate() {
        ArgumentCaptor<TriggerType> triggerType = ArgumentCaptor.forClass(TriggerType.class);
        ArgumentCaptor<String> cron = ArgumentCaptor.forClass(String.class);
        verify(workflowCrudService).createWorkflow(eq(userId), anyString(), any(), anyString(), anyString(),
            triggerType.capture(), cron.capture());
        return new Saved(triggerType.getValue(), cron.getValue());
    }

    private Saved capturedOnUpdate() {
        ArgumentCaptor<TriggerType> triggerType = ArgumentCaptor.forClass(TriggerType.class);
        ArgumentCaptor<String> cron = ArgumentCaptor.forClass(String.class);
        verify(workflowCrudService).updateWorkflow(eq(userId), eq(workflowId), anyString(), any(), anyString(),
            anyString(), triggerType.capture(), cron.capture());
        return new Saved(triggerType.getValue(), cron.getValue());
    }

    // ── 생성 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("생성: 노드만 SCHEDULE이고 최상위 triggerType·cron이 비어도 노드 값으로 저장된다 — Quartz 등록의 근거")
    void create_nodeOnlySchedule_isUsed() {
        stubSaveSucceeds();

        workflowService.createWorkflow(userId, create(SCHEDULE_TRIGGER, ""));

        assertThat(capturedOnCreate()).isEqualTo(new Saved(TriggerType.SCHEDULE, NODE_CRON));
    }

    @Test
    @DisplayName("생성: 최상위와 노드가 다르면 노드가 이긴다 — triggerType과 cron 둘 다")
    void create_nodeWinsOverTopLevel() {
        stubSaveSucceeds();

        workflowService.createWorkflow(userId, create(SCHEDULE_TRIGGER,
            ", \"triggerType\": \"MANUAL\", \"cronExpression\": \"0 0 1 * * ?\""));

        assertThat(capturedOnCreate()).isEqualTo(new Saved(TriggerType.SCHEDULE, NODE_CRON));
    }

    @Test
    @DisplayName("생성: 노드가 triggerType만 선언하고 cron이 없으면 cron은 null이다 — 최상위 cron으로 채우지 않는다")
    void create_nodeWithoutCron_doesNotBorrowTopLevelCron() {
        stubSaveSucceeds();
        String triggerWithoutCron = "{ \"id\": \"t\", \"type\": \"TRIGGER\", \"label\": \"시작\","
            + " \"config\": { \"triggerType\": \"SCHEDULE\" } }";

        workflowService.createWorkflow(userId, create(triggerWithoutCron,
            ", \"cronExpression\": \"0 0 1 * * ?\""));

        // 저장 쪽(WorkflowCrudService)이 SCHEDULE + cron 없음을 400으로 막는다
        assertThat(capturedOnCreate()).isEqualTo(new Saved(TriggerType.SCHEDULE, null));
    }

    @Test
    @DisplayName("생성: 노드에 triggerType이 없으면 기존처럼 요청 최상위 값을 쓴다(하위호환)")
    void create_nodeWithoutTriggerType_usesTopLevel() {
        stubSaveSucceeds();
        String plainTrigger = "{ \"id\": \"t\", \"type\": \"TRIGGER\", \"label\": \"시작\", \"config\": {} }";

        workflowService.createWorkflow(userId, create(plainTrigger,
            ", \"triggerType\": \"SCHEDULE\", \"cronExpression\": \"0 0 1 * * ?\""));

        assertThat(capturedOnCreate()).isEqualTo(new Saved(TriggerType.SCHEDULE, "0 0 1 * * ?"));
    }

    @Test
    @DisplayName("생성: 노드의 triggerType이 알 수 없는 값이면 400이고 아무것도 저장하지 않는다")
    void create_unknownNodeTriggerType_isRejected() {
        String bad = "{ \"id\": \"t\", \"type\": \"TRIGGER\", \"label\": \"시작\","
            + " \"config\": { \"triggerType\": \"DAILY\" } }";

        assertThatThrownBy(() -> workflowService.createWorkflow(userId, create(bad, "")))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW));

        verify(workflowCrudService, never()).createWorkflow(
            any(), any(), any(), any(), any(), any(), any());
    }

    // ── 수정 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("수정: 노드만 SCHEDULE이고 최상위가 비어도 노드 값으로 바뀐다(저장돼 있던 MANUAL을 이어받지 않는다)")
    void update_nodeOnlySchedule_isUsed() {
        stubStoredWorkflow(TriggerType.MANUAL, null);
        stubPreviousNodes(null);
        stubSaveSucceeds();

        workflowService.updateWorkflow(userId, workflowId, update(SCHEDULE_TRIGGER, ""));

        assertThat(capturedOnUpdate()).isEqualTo(new Saved(TriggerType.SCHEDULE, NODE_CRON));
    }

    @Test
    @DisplayName("수정: 요청이 config를 생략해 이전 TRIGGER config가 병합으로 되살아나도 그 노드 값이 기준이다")
    void update_usesMergedNodesNotRequestNodes() {
        stubStoredWorkflow(TriggerType.MANUAL, null);
        stubPreviousNodes(List.of(Map.of("id", "t", "type", "TRIGGER", "label", "시작",
            "config", Map.of("triggerType", "SCHEDULE", "cron", "0 30 8 * * ?"))));
        stubSaveSucceeds();

        // config 없는 요청 — 요청 노드만 보면 triggerType을 찾지 못한다
        workflowService.updateWorkflow(userId, workflowId, update(
            "{ \"id\": \"t\", \"type\": \"TRIGGER\", \"label\": \"시작\" }", ""));

        assertThat(capturedOnUpdate()).isEqualTo(new Saved(TriggerType.SCHEDULE, "0 30 8 * * ?"));
    }

    @Test
    @DisplayName("수정: 노드가 SCHEDULE인데 cron이 없으면 저장돼 있던 cron이 되살아나지 않고 null로 넘어간다")
    void update_nodeWithoutCron_doesNotReviveStoredCron() {
        stubStoredWorkflow(TriggerType.SCHEDULE, "0 0 3 * * ?");
        stubPreviousNodes(null);
        stubSaveSucceeds();
        String triggerWithoutCron = "{ \"id\": \"t\", \"type\": \"TRIGGER\", \"label\": \"시작\","
            + " \"config\": { \"triggerType\": \"SCHEDULE\" } }";

        workflowService.updateWorkflow(userId, workflowId, update(triggerWithoutCron, ""));

        assertThat(capturedOnUpdate()).isEqualTo(new Saved(TriggerType.SCHEDULE, null));
    }

    @Test
    @DisplayName("수정: 노드에 triggerType이 없으면 기존 규칙대로 최상위 생략 시 저장된 값을 잇는다(하위호환)")
    void update_nodeWithoutTriggerType_keepsLegacyFallback() {
        stubStoredWorkflow(TriggerType.SCHEDULE, "0 0 3 * * ?");
        stubPreviousNodes(null);
        stubSaveSucceeds();

        workflowService.updateWorkflow(userId, workflowId, update(
            "{ \"id\": \"t\", \"type\": \"TRIGGER\", \"label\": \"시작\", \"config\": {} }", ""));

        assertThat(capturedOnUpdate()).isEqualTo(new Saved(TriggerType.SCHEDULE, "0 0 3 * * ?"));
    }

    @Test
    @DisplayName("수정: 노드의 triggerType이 알 수 없는 값이면 400이고 새 버전을 만들지 않는다")
    void update_unknownNodeTriggerType_isRejected() {
        stubStoredWorkflow(TriggerType.MANUAL, null);
        stubPreviousNodes(null);
        String bad = "{ \"id\": \"t\", \"type\": \"TRIGGER\", \"label\": \"시작\","
            + " \"config\": { \"triggerType\": \"DAILY\" } }";

        assertThatThrownBy(() -> workflowService.updateWorkflow(userId, workflowId, update(bad, "")))
            .isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW));

        verify(workflowCrudService, never()).updateWorkflow(
            any(), any(), any(), any(), any(), any(), any(), any());
    }
}
