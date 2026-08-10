package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.credential.service.CredentialService;
import com.ieum.api.webhookcredential.domain.WebhookCredential;
import com.ieum.api.webhookcredential.service.WebhookCredentialService;
import com.ieum.api.workflow.WorkflowExecutionRunner;
import com.ieum.api.workflow.dto.NodeDto;
import com.ieum.api.workflow.dto.WorkflowResponse;
import com.ieum.common.dto.PageResponse;
import com.ieum.workflowcore.document.WorkflowDefinitionDocument;
import com.ieum.workflowcore.domain.Workflow;
import com.ieum.workflowcore.domain.WorkflowVersion;
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 조회 응답이 노드가 참조하는 웹훅 자격증명의 별칭을 싣는지 본다 (IEUM-BE-62 Task 3).
 *
 * <p>노드 config에는 UUID만 남으므로(Task 1·2), 빌더가 사람이 읽는 이름을 보여주려면 응답이 별칭을
 * 함께 줘야 한다. 별칭은 {@code WorkflowResponse.webhookCredentialNames}에 워크플로우당 하나의 사전으로
 * 실리며, 노드 JSON은 건드리지 않는다.
 */
class WorkflowWebhookCredentialNameTest {

    private final WorkflowCrudService workflowCrudService = mock(WorkflowCrudService.class);
    private final WebhookCredentialService webhookCredentialService =
        mock(WebhookCredentialService.class);
    private final WorkflowService workflowService = new WorkflowService(
        workflowCrudService,
        mock(WorkflowExecutionService.class),
        mock(WorkflowExecutionRunner.class),
        mock(ExecutionEventPublisher.class),
        new ObjectMapper(),
        webhookCredentialService,
        mock(CredentialService.class));

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final UUID slackCredentialId = UUID.randomUUID();

    /** id를 가진 자격증명. 엔티티의 id는 영속 시점에 생성돼 단위 테스트에서 심을 수 없다. */
    private WebhookCredential credential(UUID id, String displayName) {
        WebhookCredential credential = mock(WebhookCredential.class);
        given(credential.getId()).willReturn(id);
        given(credential.getDisplayName()).willReturn(displayName);
        return credential;
    }

    private void ownsCredentials(WebhookCredential... credentials) {
        given(webhookCredentialService.getByUserId(userId)).willReturn(List.of(credentials));
    }

    /** HTTP 노드 — Task 1이 만든 참조 자리({@code config.webhookCredentialId}). */
    private Map<String, Object> httpNode(String id, Object credentialId) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("method", "POST");
        if (credentialId != null) {
            config.put("webhookCredentialId", credentialId);
        }
        return node(id, "HTTP", config);
    }

    /** AI 노드 — agent가 쓰는 참조 자리({@code config.tools[].config.webhookCredentialId}). */
    private Map<String, Object> aiNodeWithSlackTool(String id, Object credentialId) {
        Map<String, Object> toolConfig = new LinkedHashMap<>();
        toolConfig.put("webhookCredentialId", credentialId);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "slack");
        tool.put("config", toolConfig);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("llmProvider", "GEMINI");
        config.put("tools", List.of(tool));
        return node(id, "AI", config);
    }

    private Map<String, Object> node(String id, String type, Map<String, Object> config) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("label", "노드");
        node.put("description", "설명이에요.");
        node.put("position", Map.of("x", 40, "y", 120));
        node.put("config", config);
        return node;
    }

    private WorkflowResponse getWorkflow(List<Map<String, Object>> nodes) {
        Workflow workflow = mock(Workflow.class);
        WorkflowVersion version = mock(WorkflowVersion.class);
        given(workflowCrudService.getWorkflowByOwner(userId, workflowId)).willReturn(workflow);
        given(workflowCrudService.findLatestVersion(workflowId)).willReturn(Optional.of(version));
        given(workflowCrudService.loadDefinition(version)).willReturn(
            WorkflowDefinitionDocument.builder().nodes(nodes).edges(List.of()).build());
        return workflowService.getWorkflow(userId, workflowId);
    }

    @Test
    @DisplayName("HTTP 노드의 webhookCredentialId에 해당하는 별칭이 응답에 실린다")
    void httpNodeReference_carriesDisplayName() {
        ownsCredentials(credential(slackCredentialId, "팀 슬랙 알림"));

        WorkflowResponse response = getWorkflow(List.of(
            httpNode("node-1", slackCredentialId.toString())));

        assertThat(response.getWebhookCredentialNames())
            .containsExactly(Map.entry(slackCredentialId.toString(), "팀 슬랙 알림"));
    }

    /**
     * AI 노드의 참조는 {@code config.tools[]} 안에 중첩돼 있다. HTTP 노드 자리만 보면 기존
     * 워크플로우 대부분(agent가 만든 slack/discord 도구)이 빌더에서 UUID로 남는다.
     */
    @Test
    @DisplayName("AI 노드 tools[] 안의 webhookCredentialId도 별칭이 실린다")
    void aiNodeToolReference_carriesDisplayName() {
        ownsCredentials(credential(slackCredentialId, "팀 슬랙 알림"));

        WorkflowResponse response = getWorkflow(List.of(
            aiNodeWithSlackTool("node-1", slackCredentialId.toString())));

        assertThat(response.getWebhookCredentialNames())
            .containsEntry(slackCredentialId.toString(), "팀 슬랙 알림");
    }

    /**
     * 소유자 검증. 사전은 요청 사용자 소유분으로만 만들어지므로, 남의 id를 config에 박아 넣어도
     * 이름이 붙지 않는다 — 별칭도 URL과 같은 기준으로 새면 안 된다.
     */
    @Test
    @DisplayName("남의 크레덴셜을 참조하면 별칭이 실리지 않는다")
    void otherUsersCredential_isNotNamed() {
        UUID otherUsersCredentialId = UUID.randomUUID();
        ownsCredentials(credential(slackCredentialId, "내 슬랙"));

        WorkflowResponse response = getWorkflow(List.of(
            httpNode("node-1", otherUsersCredentialId.toString()),
            aiNodeWithSlackTool("node-2", otherUsersCredentialId.toString())));

        assertThat(response.getWebhookCredentialNames()).isEmpty();
        // 사전은 반드시 요청 사용자 기준으로 조회돼야 한다 — 이 조회가 소유자 검증 그 자체다.
        verify(webhookCredentialService).getByUserId(userId);
    }

    /**
     * 삭제된 id·형식이 깨진 id·id 자리에 들어간 URL. 조회 응답 매핑은 어떤 입력에도 예외를 던지지
     * 않는다(IEUM-BE-60) — 이름을 못 찾으면 이름 없이 반환한다.
     */
    @Test
    @DisplayName("없는·삭제된·형식이 깨진 크레덴셜을 참조해도 조회는 성공하고 이름만 빠진다")
    void unresolvableReference_doesNotBreakRead() {
        ownsCredentials(credential(slackCredentialId, "내 슬랙"));

        WorkflowResponse response = getWorkflow(List.of(
            httpNode("node-1", UUID.randomUUID().toString()),
            httpNode("node-2", "삭제된-크레덴셜"),
            httpNode("node-3", "https://hooks.slack.com/services/T0/B0/XXXX"),
            httpNode("node-4", Map.of("nested", "object")),
            httpNode("node-5", null),
            httpNode("node-6", slackCredentialId.toString())));

        assertThat(response.getNodes()).hasSize(6);
        assertThat(response.getWebhookCredentialNames())
            .containsExactly(Map.entry(slackCredentialId.toString(), "내 슬랙"));
    }

    /** 자기 참조하는 config에서도 탐색이 멈춰야 한다 — 정의 문서 구조는 BE가 통제하지 못한다. */
    @Test
    @DisplayName("순환 참조하는 config에도 조회가 예외 없이 끝난다")
    void cyclicConfig_doesNotBreakRead() {
        ownsCredentials(credential(slackCredentialId, "내 슬랙"));
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("webhookCredentialId", slackCredentialId.toString());
        cyclic.put("self", cyclic);

        assertThatCode(() -> {
            WorkflowResponse response = getWorkflow(List.of(node("node-1", "HTTP", cyclic)));
            assertThat(response.getWebhookCredentialNames())
                .containsEntry(slackCredentialId.toString(), "내 슬랙");
        }).doesNotThrowAnyException();
    }

    /**
     * 별칭은 응답 전용 파생값이다. 노드 JSON에 필드가 늘거나 config에 이름이 섞이면 프론트가
     * 그대로 저장할 때 정의로 되써진다.
     */
    @Test
    @DisplayName("웹훅과 무관한 노드의 JSON 모양이 요청 DTO와 그대로 같다")
    void unrelatedNode_keepsSameJsonShape() throws Exception {
        ownsCredentials(credential(slackCredentialId, "내 슬랙"));
        String nodeJson = """
            { "id": "node-1", "type": "AI", "label": "분류", "description": "설명이에요.",
              "position": { "x": 40.0, "y": 120.0 },
              "config": { "llmProvider": "GEMINI" } }""";

        WorkflowResponse response = getWorkflow(List.of(
            jsonMapper.readValue(nodeJson, new TypeReference<Map<String, Object>>() {})));

        JsonNode viewTree = tree(response.getNodes().get(0));
        assertThat(viewTree).isEqualTo(tree(jsonMapper.readValue(nodeJson, NodeDto.class)));
        assertThat(response.getWebhookCredentialNames()).isEmpty();
    }

    /** 참조가 있는 노드도 노드 JSON은 그대로다 — 별칭은 워크플로우 레벨 사전에만 실린다. */
    @Test
    @DisplayName("웹훅을 참조하는 노드의 JSON에도 필드가 늘지 않고 config가 원본 그대로다")
    void referencingNode_keepsConfigUntouched() throws Exception {
        ownsCredentials(credential(slackCredentialId, "팀 슬랙 알림"));

        WorkflowResponse response = getWorkflow(List.of(
            httpNode("node-1", slackCredentialId.toString())));

        JsonNode viewTree = tree(response.getNodes().get(0));
        assertThat(fieldNames(viewTree))
            .containsExactlyInAnyOrder("id", "type", "label", "description", "position", "config");
        assertThat(fieldNames(viewTree.get("config")))
            .containsExactlyInAnyOrder("method", "webhookCredentialId");
        assertThat(viewTree.at("/config/webhookCredentialId").asText())
            .isEqualTo(slackCredentialId.toString());
    }

    /**
     * 목록 조회는 워크플로우를 한 번에 변환한다. 워크플로우마다·노드마다 자격증명을 조회하면
     * 쿼리가 폭발하므로, 사전은 페이지 전체에 한 번만 만들어져야 한다.
     */
    @Test
    @DisplayName("목록 조회는 워크플로우가 여러 건이어도 자격증명을 한 번만 조회한다")
    void listRead_loadsCredentialsOnce() {
        ownsCredentials(credential(slackCredentialId, "팀 슬랙 알림"));

        Workflow first = mock(Workflow.class);
        Workflow second = mock(Workflow.class);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        given(first.getId()).willReturn(firstId);
        given(second.getId()).willReturn(secondId);
        WorkflowVersion firstVersion = mock(WorkflowVersion.class);
        WorkflowVersion secondVersion = mock(WorkflowVersion.class);

        given(workflowCrudService.listWorkflows(userId, 0, 20)).willReturn(List.of(first, second));
        given(workflowCrudService.hasNextWorkflows(userId, 0, 20)).willReturn(false);
        given(workflowCrudService.getLatestVersionMap(List.of(firstId, secondId)))
            .willReturn(Map.of(firstId, firstVersion, secondId, secondVersion));
        given(workflowCrudService.loadDefinition(firstVersion)).willReturn(
            WorkflowDefinitionDocument.builder()
                .nodes(List.of(httpNode("node-1", slackCredentialId.toString())))
                .edges(List.of()).build());
        given(workflowCrudService.loadDefinition(secondVersion)).willReturn(
            WorkflowDefinitionDocument.builder()
                .nodes(List.of(aiNodeWithSlackTool("node-2", slackCredentialId.toString())))
                .edges(List.of()).build());

        PageResponse<WorkflowResponse> page = workflowService.getWorkflows(userId, null, 20);

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getWebhookCredentialNames())
            .containsEntry(slackCredentialId.toString(), "팀 슬랙 알림");
        assertThat(page.getContent().get(1).getWebhookCredentialNames())
            .containsEntry(slackCredentialId.toString(), "팀 슬랙 알림");
        verify(webhookCredentialService, times(1)).getByUserId(userId);
    }

    private JsonNode tree(Object value) throws Exception {
        return jsonMapper.readTree(jsonMapper.writeValueAsString(value));
    }

    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
