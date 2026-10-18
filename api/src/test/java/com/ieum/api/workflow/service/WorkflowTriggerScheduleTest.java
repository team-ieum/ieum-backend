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
import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.catalog.NodeDerivation;
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
 * REST 생성·수정이 워크플로우 트리거 타입·cron을 노드에서 계산하고, 요청 최상위 값은 검증에만 쓰는지 본다 (IEUM-BE-92).
 * 첫 번째 TRIGGER 노드의 event가 트리거 종류이고, schedule.every면 그 노드 config.cron이 cron이다. 트리거 노드가 없으면
 * MANUAL·cron null. 최상위 값은 null이면 통과, 계산값과 다르면 400이다. 수정은 직전 정의와 병합한 결과가 기준이다.
 * 계산 규칙 자체는 {@link TriggerNodeScheduleTest}가 본다.
 */
class WorkflowTriggerScheduleTest {

    private static final EventCatalog CATALOG = EventCatalog.loadFromClasspath();

    private static final String NODE_CRON = "0 0 10 * * ?";
    private static final String SCHEDULE_TRIGGER =
        "{ \"id\": \"t\", \"event\": \"schedule.every\", \"label\": \"시작\","
            + " \"config\": { \"cron\": \"" + NODE_CRON + "\" } }";
    private static final String WEBHOOK_TRIGGER =
        "{ \"id\": \"t\", \"event\": \"webhook.catch\", \"label\": \"시작\", \"config\": {} }";
    private static final String TRANSFORM_NODE =
        "{ \"id\": \"n\", \"event\": \"transform.map\", \"label\": \"가공\", \"config\": { \"mappings\": {} } }";

    private final WorkflowCrudService workflowCrudService = mock(WorkflowCrudService.class);
    private final WorkflowService workflowService = new WorkflowService(
        workflowCrudService,
        mock(WorkflowExecutionService.class),
        mock(WorkflowExecutionRunner.class),
        mock(ExecutionEventPublisher.class),
        new ObjectMapper(),
        mock(WebhookCredentialService.class),
        mock(CredentialService.class),
        new NodeDerivation(CATALOG), CATALOG);

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

    private void assertCreateRejected(String nodeJson, String extraFields, String expectedInMessage) {
        assertThatThrownBy(() -> workflowService.createWorkflow(userId, create(nodeJson, extraFields)))
            .isInstanceOfSatisfying(CustomException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW);
                assertThat(e.getMessage()).contains(expectedInMessage);
            });
        verify(workflowCrudService, never()).createWorkflow(any(), any(), any(), any(), any(), any(), any());
    }

    // ── 생성 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("생성: schedule.every 노드면 최상위가 비어도 SCHEDULE·노드 cron으로 저장된다 — Quartz 등록의 근거")
    void create_scheduleNode_isUsed() {
        stubSaveSucceeds();

        workflowService.createWorkflow(userId, create(SCHEDULE_TRIGGER, ""));

        assertThat(capturedOnCreate()).isEqualTo(new Saved(TriggerType.SCHEDULE, NODE_CRON));
    }

    @Test
    @DisplayName("생성: 트리거 노드가 여럿이면 첫 번째가 기준이다")
    void create_firstTriggerWins() {
        stubSaveSucceeds();

        workflowService.createWorkflow(userId, create(
            WEBHOOK_TRIGGER + ", " + SCHEDULE_TRIGGER.replace("\"t\"", "\"t2\""), ""));

        assertThat(capturedOnCreate()).isEqualTo(new Saved(TriggerType.WEBHOOK, null));
    }

    @Test
    @DisplayName("생성: 빈 워크플로우(nodes [])에 최상위 MANUAL을 보내면 통과하고 MANUAL로 저장된다")
    void create_emptyWorkflowWithManual_passes() {
        stubSaveSucceeds();

        workflowService.createWorkflow(userId, create("", ", \"triggerType\": \"MANUAL\""));

        assertThat(capturedOnCreate()).isEqualTo(new Saved(TriggerType.MANUAL, null));
    }

    @Test
    @DisplayName("생성: 최상위 cron이 노드 cron과 Quartz 정규화로 같으면 통과하고 노드 원문을 넘긴다")
    void create_sameCronAfterNormalization_passes() {
        stubSaveSucceeds();
        String unixTrigger = "{ \"id\": \"t\", \"event\": \"schedule.every\", \"label\": \"시작\","
            + " \"config\": { \"cron\": \"0 10 * * *\" } }";

        workflowService.createWorkflow(userId, create(unixTrigger,
            ", \"triggerType\": \"SCHEDULE\", \"cronExpression\": \"" + NODE_CRON + "\""));

        assertThat(capturedOnCreate()).isEqualTo(new Saved(TriggerType.SCHEDULE, "0 10 * * *"));
    }

    @Test
    @DisplayName("생성: schedule.every 노드에 cron이 없으면 400 INVALID_CRON_EXPRESSION — 메시지에 노드 id, 저장하지 않는다")
    void create_scheduleNodeWithoutCron_isRejected() {
        String triggerWithoutCron =
            "{ \"id\": \"t\", \"event\": \"schedule.every\", \"label\": \"시작\", \"config\": {} }";

        // 최상위 cron을 보내도 최상위 비교(INVALID_WORKFLOW)보다 이 거부가 먼저다
        assertThatThrownBy(() -> workflowService.createWorkflow(userId, create(triggerWithoutCron,
                ", \"cronExpression\": \"" + NODE_CRON + "\"")))
            .isInstanceOfSatisfying(CustomException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CRON_EXPRESSION);
                assertThat(e.getMessage()).isEqualTo("schedule.every 노드(t)의 config.cron이 필요합니다");
            });
        verify(workflowCrudService, never()).createWorkflow(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("생성: 최상위 triggerType이 노드 계산값과 다르면 400이고 메시지에 계산값이 실린다")
    void create_triggerTypeMismatch_isRejected() {
        assertCreateRejected(SCHEDULE_TRIGGER, ", \"triggerType\": \"MANUAL\"", "SCHEDULE");
    }

    @Test
    @DisplayName("생성: 최상위 cron이 노드 cron과 다르면 400")
    void create_cronMismatch_isRejected() {
        assertCreateRejected(SCHEDULE_TRIGGER,
            ", \"triggerType\": \"SCHEDULE\", \"cronExpression\": \"0 0 1 * * ?\"", NODE_CRON);
    }

    @Test
    @DisplayName("생성: SCHEDULE이 아닌데 최상위 cron을 보내면 400")
    void create_cronOnNonSchedule_isRejected() {
        assertCreateRejected(WEBHOOK_TRIGGER, ", \"cronExpression\": \"0 0 1 * * ?\"", "WEBHOOK");
    }

    @Test
    @DisplayName("생성: 트리거 노드 없이 최상위 SCHEDULE·cron만 보내면 400 — 노드 없는 스케줄 등록 경로는 없다")
    void create_topLevelScheduleWithoutTriggerNode_isRejected() {
        assertCreateRejected(TRANSFORM_NODE,
            ", \"triggerType\": \"SCHEDULE\", \"cronExpression\": \"" + NODE_CRON + "\"", "MANUAL");
    }

    // ── 수정 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("수정: schedule.every 노드면 최상위가 비어도 노드 값으로 바뀐다(저장돼 있던 MANUAL을 잇지 않는다)")
    void update_scheduleNode_isUsed() {
        stubStoredWorkflow(TriggerType.MANUAL, null);
        stubPreviousNodes(null);
        stubSaveSucceeds();

        workflowService.updateWorkflow(userId, workflowId, update(SCHEDULE_TRIGGER, ""));

        assertThat(capturedOnUpdate()).isEqualTo(new Saved(TriggerType.SCHEDULE, NODE_CRON));
    }

    @Test
    @DisplayName("수정: 요청이 config를 생략해 이전 트리거 config가 병합으로 되살아나도 그 노드 값이 기준이다")
    void update_usesMergedNodesNotRequestNodes() {
        stubStoredWorkflow(TriggerType.MANUAL, null);
        stubPreviousNodes(List.of(Map.of("id", "t", "event", "schedule.every", "type", "TRIGGER", "label", "시작",
            "config", Map.of("cron", "0 30 8 * * ?"))));
        stubSaveSucceeds();

        workflowService.updateWorkflow(userId, workflowId, update(
            "{ \"id\": \"t\", \"event\": \"schedule.every\", \"label\": \"시작\" }", ""));

        assertThat(capturedOnUpdate()).isEqualTo(new Saved(TriggerType.SCHEDULE, "0 30 8 * * ?"));
    }

    @Test
    @DisplayName("수정: 트리거 노드가 없으면 저장돼 있던 SCHEDULE이어도 MANUAL·cron null — 저장값 폴백은 없다")
    void update_noTriggerNode_isManualEvenIfStoredSchedule() {
        stubStoredWorkflow(TriggerType.SCHEDULE, "0 0 3 * * ?");
        stubPreviousNodes(null);
        stubSaveSucceeds();

        workflowService.updateWorkflow(userId, workflowId, update(TRANSFORM_NODE, ""));

        assertThat(capturedOnUpdate()).isEqualTo(new Saved(TriggerType.MANUAL, null));
    }

    @Test
    @DisplayName("수정: 최상위 triggerType이 계산값과 다르면 400이고 새 버전을 만들지 않는다")
    void update_triggerTypeMismatch_isRejected() {
        stubStoredWorkflow(TriggerType.SCHEDULE, "0 0 3 * * ?");
        stubPreviousNodes(null);

        assertThatThrownBy(() -> workflowService.updateWorkflow(userId, workflowId,
                update(TRANSFORM_NODE, ", \"triggerType\": \"SCHEDULE\"")))
            .isInstanceOfSatisfying(CustomException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW);
                assertThat(e.getMessage()).contains("MANUAL");
            });

        verify(workflowCrudService, never()).updateWorkflow(
            any(), any(), any(), any(), any(), any(), any(), any());
    }
}
