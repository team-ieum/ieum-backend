package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.credential.service.CredentialService;
import com.ieum.api.webhookcredential.service.WebhookCredentialService;
import com.ieum.api.workflow.WorkflowExecutionRunner;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/**
 * 수정 요청이 보내지 않은 노드 필드가 직전 버전에서 살아남는지, 그리고 그 병합 결과가 저장 전에
 * 가드를 거치는지 본다 (IEUM-BE-65 Task 2 — 배선 검증).
 *
 * <p>병합 규칙 자체는 {@link NodeDefinitionMergerTest}·{@link EdgeDefinitionMergerTest}가 본다.
 * 여기서 고정하는 계약은 네 가지다 — ① 이전 정의를 실제로 읽어 병합한 결과가 저장된다
 * ② 가드가 요청이 아니라 병합 결과에 돈다 ③ 소유권 검증이 이전 정의 조회보다 먼저 온다
 * ④ 노드와 엣지가 같은 문서 한 번의 조회에서 온다.
 *
 * <p>노드 하위 필드뿐 아니라 워크플로우 레벨 optional 필드(description·triggerType·cronExpression)도
 * 같은 규칙을 따르는지 함께 본다 — 여기가 새면 SCHEDULE 워크플로우가 조용히 MANUAL로 강등된다.
 */
class WorkflowPartialUpdateTest {

    private final WorkflowCrudService workflowCrudService = mock(WorkflowCrudService.class);
    private final CredentialService credentialService = mock(CredentialService.class);
    private final WorkflowService workflowService = new WorkflowService(
        workflowCrudService,
        mock(WorkflowExecutionService.class),
        mock(WorkflowExecutionRunner.class),
        mock(ExecutionEventPublisher.class),
        new ObjectMapper(),
        mock(WebhookCredentialService.class),
        credentialService);

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    private static final String NODE_ID = "node-ai";

    /** 저장돼 있는 워크플로우. 수정 요청이 생략한 필드의 폴백 원천이다. */
    private final Workflow currentWorkflow = mock(Workflow.class);

    @BeforeEach
    void stubCurrentWorkflow() {
        given(workflowCrudService.getWorkflowByOwner(userId, workflowId))
            .willReturn(currentWorkflow);
    }

    /** 직전 버전 정의를 스텁한다. {@code null}이면 버전 자체가 없는 워크플로우다. */
    private void stubPreviousNodes(List<Map<String, Object>> previousNodes) {
        stubPreviousDefinition(previousNodes, List.of());
    }

    /**
     * 직전 버전 정의를 스텁하고 그 버전을 돌려준다. {@code previousNodes}가 {@code null}이면 버전
     * 자체가 없는 워크플로우이고 {@code null}을 돌려준다.
     */
    private WorkflowVersion stubPreviousDefinition(List<Map<String, Object>> previousNodes,
            List<Map<String, Object>> previousEdges) {
        if (previousNodes == null) {
            given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.empty());
            return null;
        }
        WorkflowVersion previousVersion = mock(WorkflowVersion.class);
        given(workflowCrudService.findLatestVersion(workflowId))
            .willReturn(Optional.of(previousVersion));
        given(workflowCrudService.loadDefinition(previousVersion)).willReturn(
            WorkflowDefinitionDocument.builder()
                .nodes(previousNodes).edges(previousEdges).build());
        return previousVersion;
    }

    /** 저장이 성공하는 경로. 응답 변환까지 가려면 crud 반환값이 필요하다. */
    private void stubSaveSucceeds() {
        Workflow workflow = mock(Workflow.class);
        WorkflowVersion savedVersion = mock(WorkflowVersion.class);
        given(savedVersion.getWorkflow()).willReturn(workflow);
        given(workflowCrudService.updateWorkflow(
            any(), any(), any(), any(), any(), any(), any(), any())).willReturn(savedVersion);
        given(workflowCrudService.loadDefinition(savedVersion)).willReturn(
            WorkflowDefinitionDocument.builder().nodes(List.of()).edges(List.of()).build());
    }

    private UpdateWorkflowRequest request(String nodeJson) {
        return request(nodeJson, "");
    }

    /** {@code extraFields}는 워크플로우 레벨 필드를 덧붙이는 JSON 조각이다(예: {@code ,"triggerType":"MANUAL"}). */
    private UpdateWorkflowRequest request(String nodeJson, String extraFields) {
        return request(nodeJson, "", extraFields);
    }

    private UpdateWorkflowRequest request(String nodeJson, String edgeJson, String extraFields) {
        String body = """
            {
              "name": "수정된 워크플로우",
              "nodes": [ %s ],
              "edges": [ %s ]%s
            }""".formatted(nodeJson, edgeJson, extraFields);
        try {
            return jsonMapper.readValue(body, UpdateWorkflowRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 저장 경로로 넘어간 nodesJson을 파싱해 돌려준다. */
    private List<Map<String, Object>> capturedNodes() {
        ArgumentCaptor<String> nodesJson = ArgumentCaptor.forClass(String.class);
        verify(workflowCrudService).updateWorkflow(eq(userId), eq(workflowId), any(), any(),
            nodesJson.capture(), any(), any(), any());
        return parseDefinitionJson(nodesJson.getValue());
    }

    /** 저장 경로로 넘어간 edgesJson을 파싱해 돌려준다. */
    private List<Map<String, Object>> capturedEdges() {
        ArgumentCaptor<String> edgesJson = ArgumentCaptor.forClass(String.class);
        verify(workflowCrudService).updateWorkflow(eq(userId), eq(workflowId), any(), any(),
            any(), edgesJson.capture(), any(), any());
        return parseDefinitionJson(edgesJson.getValue());
    }

    private List<Map<String, Object>> parseDefinitionJson(String json) {
        try {
            return jsonMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 엣지의 {@code conditionType}은 선택 필드라, 생략한 PUT이 저장된 {@code "true"}/{@code "false"}를
     * 지우면 실행 시 {@code ExecutionCursor.liveOutgoingEdges}가 걸러 낼 엣지가 하나도 남지 않아
     * 조건 노드 이후가 조용히 실행되지 않는다 (IEUM-BE-65).
     *
     * <p>병합 규칙 자체는 {@link EdgeDefinitionMergerTest}가 본다. 여기서 고정하는 것은 배선과,
     * 노드·엣지가 <b>같은 문서 한 번의 조회</b>에서 온다는 사실이다.
     */
    @Test
    @DisplayName("엣지 conditionType을 생략해도 이전 값이 보존되고, 직전 정의는 한 번만 읽는다")
    void keepsEdgeConditionTypeReadingPreviousDefinitionOnce() {
        WorkflowVersion previousVersion = stubPreviousDefinition(
            List.of(Map.of("id", NODE_ID, "type", "AI", "label", "요약하기")),
            List.of(Map.of("source", NODE_ID, "target", "n-yes", "conditionType", "true")));
        stubSaveSucceeds();

        workflowService.updateWorkflow(userId, workflowId, request(
            "{ \"id\": \"" + NODE_ID + "\", \"type\": \"AI\", \"label\": \"요약하기\" }",
            "{ \"source\": \"" + NODE_ID + "\", \"target\": \"n-yes\" }",
            ""));

        List<Map<String, Object>> savedEdges = capturedEdges();
        assertThat(savedEdges).hasSize(1);
        assertThat(savedEdges.get(0).get("conditionType")).isEqualTo("true");
        // 직전 정의는 한 번만 읽는다 — 노드와 엣지가 같은 문서에서 온다.
        // (any()로 세지 않는 이유는 응답 변환이 '저장된' 버전의 정의를 따로 읽기 때문이다.)
        verify(workflowCrudService, times(1)).loadDefinition(previousVersion);
    }

    @Test
    @DisplayName("요청이 생략한 description·position은 직전 버전 값으로 저장된다")
    void keepsOmittedFieldsFromPreviousVersion() {
        stubPreviousNodes(List.of(Map.of(
            "id", NODE_ID,
            "type", "AI",
            "label", "요약하기",
            "description", "글을 요약해요.",
            "position", Map.of("x", 40, "y", 120),
            "config", Map.of("model", "gemini-3.5-flash"))));
        stubSaveSucceeds();

        // FE가 보내는 최소 요청 — description·position이 없다.
        workflowService.updateWorkflow(userId, workflowId, request("""
            { "id": "%s", "type": "AI", "label": "요약하기 v2",
              "config": { "model": "gemini-3.5-flash" } }""".formatted(NODE_ID)));

        Map<String, Object> saved = capturedNodes().get(0);
        assertThat(saved.get("description")).isEqualTo("글을 요약해요.");
        assertThat(saved.get("position")).isEqualTo(Map.of("x", 40, "y", 120));
        // 요청이 보낸 값은 이전 값을 덮어쓴다.
        assertThat(saved.get("label")).isEqualTo("요약하기 v2");
    }

    @Test
    @DisplayName("요청이 config를 생략해 이전 config가 되살아나면, 그 config에도 가드가 돈다")
    void guardsRunOnRevivedPreviousConfig() {
        UUID foreignCredentialId = UUID.randomUUID();
        stubPreviousNodes(List.of(Map.of(
            "id", NODE_ID,
            "type", "AI",
            "label", "요약하기",
            "config", Map.of("credentialId", foreignCredentialId.toString()))));
        given(credentialService.getByUserId(userId)).willReturn(List.of());

        // config 없는 요청 — 요청만 검사하면 아무것도 걸리지 않는다.
        UpdateWorkflowRequest request = request(
            "{ \"id\": \"" + NODE_ID + "\", \"type\": \"AI\", \"label\": \"요약하기\" }");

        assertThatThrownBy(() -> workflowService.updateWorkflow(userId, workflowId, request))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_WORKFLOW))
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode().getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageNotContaining(foreignCredentialId.toString());

        // 거부됐으면 새 버전이 남지 않아야 한다.
        verify(workflowCrudService, never()).updateWorkflow(
            any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("최신 버전이 없는 워크플로우는 예외 없이 요청 노드 그대로 저장한다")
    void savesRequestNodesWhenNoPreviousVersion() {
        stubPreviousNodes(null);
        stubSaveSucceeds();

        assertThatCode(() -> workflowService.updateWorkflow(userId, workflowId, request("""
            { "id": "%s", "type": "AI", "label": "요약하기" }""".formatted(NODE_ID))))
            .doesNotThrowAnyException();

        Map<String, Object> saved = capturedNodes().get(0);
        assertThat(saved).containsOnlyKeys("id", "type", "label");
        assertThat(saved.get("id")).isEqualTo(NODE_ID);
    }

    @Test
    @DisplayName("소유권 검증이 이전 정의 조회보다 먼저다 — 남의 정의는 읽지도 않는다")
    void verifiesOwnershipBeforeReadingPreviousDefinition() {
        willThrow(new CustomException(ErrorCode.WORKFLOW_NOT_FOUND))
            .given(workflowCrudService).getWorkflowByOwner(userId, workflowId);

        UpdateWorkflowRequest request = request(
            "{ \"id\": \"" + NODE_ID + "\", \"type\": \"AI\", \"label\": \"요약하기\" }");

        assertThatThrownBy(() -> workflowService.updateWorkflow(userId, workflowId, request))
            .isInstanceOf(CustomException.class);

        verify(workflowCrudService, never()).findLatestVersion(any());
        verify(workflowCrudService, never()).loadDefinition(any());
    }

    /** 저장 경로로 넘어간 워크플로우 레벨 인자(description, triggerType, cronExpression). */
    private record SavedWorkflowFields(String description, TriggerType triggerType, String cron) {
    }

    private SavedWorkflowFields capturedWorkflowFields() {
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<TriggerType> triggerType = ArgumentCaptor.forClass(TriggerType.class);
        ArgumentCaptor<String> cron = ArgumentCaptor.forClass(String.class);
        verify(workflowCrudService).updateWorkflow(eq(userId), eq(workflowId), any(),
            description.capture(), any(), any(), triggerType.capture(), cron.capture());
        return new SavedWorkflowFields(
            description.getValue(), triggerType.getValue(), cron.getValue());
    }

    /**
     * description과 스케줄을 따로 단언하던 테스트 둘을 합쳤다 — 둘 다 같은 사실(워크플로우 레벨
     * 필드의 null은 "변경 없음")을 확인했고, 셋을 한 번에 생략한 이 요청이 그 상위 집합이다.
     */
    @Test
    @DisplayName("description·triggerType·cron을 보내지 않으면 저장된 값이 그대로 유지된다")
    void keepsWorkflowFieldsWhenOmitted() {
        given(currentWorkflow.getDescription()).willReturn("매일 아침 리포트를 보냅니다.");
        given(currentWorkflow.getTriggerType()).willReturn(TriggerType.SCHEDULE);
        given(currentWorkflow.getCronExpression()).willReturn("0 0 10 * * ?");
        stubPreviousNodes(null);
        stubSaveSucceeds();

        workflowService.updateWorkflow(userId, workflowId, request("""
            { "id": "%s", "type": "AI", "label": "요약하기" }""".formatted(NODE_ID)));

        SavedWorkflowFields saved = capturedWorkflowFields();
        assertThat(saved.description()).isEqualTo("매일 아침 리포트를 보냅니다.");
        assertThat(saved.triggerType()).isEqualTo(TriggerType.SCHEDULE);
        assertThat(saved.cron()).isEqualTo("0 0 10 * * ?");
    }

    @Test
    @DisplayName("triggerType을 MANUAL로 명시하면 저장된 cron이 되살아나지 않는다")
    void doesNotReviveCronWhenTriggerSwitchedToManual() {
        given(currentWorkflow.getTriggerType()).willReturn(TriggerType.SCHEDULE);
        given(currentWorkflow.getCronExpression()).willReturn("0 0 10 * * ?");
        stubPreviousNodes(null);
        stubSaveSucceeds();

        workflowService.updateWorkflow(userId, workflowId, request("""
            { "id": "%s", "type": "AI", "label": "요약하기" }""".formatted(NODE_ID),
            ",\n  \"triggerType\": \"MANUAL\""));

        SavedWorkflowFields saved = capturedWorkflowFields();
        assertThat(saved.triggerType()).isEqualTo(TriggerType.MANUAL);
        assertThat(saved.cron()).isNull();
    }

    /**
     * MANUAL인데 cron이 남은 워크플로우는 실제로 만들어진다 — 생성 시 {@code validateScheduleConfig}는
     * SCHEDULE이 아니면 cron을 보지 않으므로 {@code triggerType: MANUAL} + cron을 함께 보내면 그대로
     * 저장된다. 그 상태에서 cron 없이 SCHEDULE로 켜는 요청이 그 유령 cron을 이어받으면, 사용자가
     * 이번 요청에 지정한 적 없는 시각으로 Quartz Job이 등록된다 (IEUM-BE-65).
     */
    @Test
    @DisplayName("MANUAL에 남은 유령 cron은 SCHEDULE로 켜도 되살아나지 않는다")
    void doesNotReviveOrphanCronWhenTriggerSwitchedToSchedule() {
        given(currentWorkflow.getTriggerType()).willReturn(TriggerType.MANUAL);
        given(currentWorkflow.getCronExpression()).willReturn("0 0 3 * * ?");
        stubPreviousNodes(null);
        // crud는 SCHEDULE + cron null을 400으로 거절한다 — 그 거절이 실제로 도달하는지까지 본다.
        willThrow(new CustomException(ErrorCode.INVALID_CRON_EXPRESSION))
            .given(workflowCrudService).updateWorkflow(
                any(), any(), any(), any(), any(), any(), eq(TriggerType.SCHEDULE), isNull());

        UpdateWorkflowRequest request = request("""
            { "id": "%s", "type": "AI", "label": "요약하기" }""".formatted(NODE_ID),
            ",\n  \"triggerType\": \"SCHEDULE\"");

        assertThatThrownBy(() -> workflowService.updateWorkflow(userId, workflowId, request))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CRON_EXPRESSION));

        assertThat(capturedWorkflowFields().cron()).isNull();
    }

}
