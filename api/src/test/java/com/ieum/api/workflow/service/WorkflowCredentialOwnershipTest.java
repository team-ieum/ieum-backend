package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.credential.domain.Credential;
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
import com.ieum.workflowcore.engine.event.ExecutionEventPublisher;
import com.ieum.workflowcore.service.WorkflowCrudService;
import com.ieum.workflowcore.service.WorkflowExecutionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/**
 * 노드 {@code config.credentialId}에 남의 크레덴셜이 담긴 저장을 생성·수정 양쪽에서 거부하는지 본다
 * (IEUM-BE-64 Task 2).
 *
 * <p>복호화 자체는 {@code CredentialService.decrypt}가 소유자 검증으로 이미 막는다(Task 1). 여기서
 * 확인하는 것은 두 번째 겹이다 — 저장은 되고 실행에서야 {@code NOT_FOUND}로 죽으면 사용자에게 원인이
 * 보이지 않으므로 저장에서 400으로 알린다. 400은 {@link ErrorCode#INVALID_WORKFLOW}의 상태 코드로
 * 검증한다({@code GlobalExceptionHandler}가 ErrorCode의 상태를 그대로 응답 상태로 쓴다).
 */
class WorkflowCredentialOwnershipTest {

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

    private static final UUID OWNED_CREDENTIAL_ID = UUID.randomUUID();
    private static final UUID FOREIGN_CREDENTIAL_ID = UUID.randomUUID();

    private final Credential ownedCredential =
        Credential.builder().id(OWNED_CREDENTIAL_ID).userId(userId).build();

    /** AI 노드 하나짜리 최소 요청. config만 테스트마다 바꾼다. */
    private <T> T read(Class<T> type, String aiConfig) {
        String body = """
            {
              "name": "AI 워크플로우",
              "description": "크레덴셜 소유 검증",
              "nodes": [
                { "id": "node-ai", "type": "AI", "label": "요약하기",
                  "description": "글을 요약해요.",
                  "position": { "x": 40, "y": 120 },
                  "config": %s }
              ],
              "edges": []
            }""".formatted(aiConfig);
        try {
            return jsonMapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 수정 경로의 거부는 "새 버전이 남지 않는다"로 확인한다.
     *
     * <p>{@code verifyNoInteractions}는 쓸 수 없다 — 수정은 병합할 직전 정의를 읽기 전에 소유권부터
     * 검증하므로({@code getWorkflowByOwner}) 거부돼도 crud 호출이 0은 아니다 (IEUM-BE-65).
     */
    private void verifyNoSave() {
        verify(workflowCrudService, never()).updateWorkflow(
            any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** 검증을 통과한 요청이 실제로 저장 경로까지 가는지 보려면 crud 반환값이 필요하다. */
    private void stubSaveSucceeds() {
        Workflow workflow = mock(Workflow.class);
        WorkflowVersion version = mock(WorkflowVersion.class);
        given(version.getWorkflow()).willReturn(workflow);
        given(workflowCrudService.createWorkflow(
            any(), anyString(), any(), anyString(), anyString(), any(), any()))
            .willReturn(version);
        given(workflowCrudService.updateWorkflow(
            any(), any(), anyString(), any(), anyString(), anyString(), any(), any()))
            .willReturn(version);
        given(workflowCrudService.loadDefinition(version)).willReturn(
            WorkflowDefinitionDocument.builder().nodes(List.of()).edges(List.of()).build());
    }

    @Test
    @DisplayName("남의 credentialId가 담긴 노드는 생성에서 거부한다")
    void rejectsForeignCredentialIdOnCreate() {
        given(credentialService.getByUserId(userId)).willReturn(List.of(ownedCredential));

        CreateWorkflowRequest request = read(CreateWorkflowRequest.class,
            "{ \"credentialId\": \"" + FOREIGN_CREDENTIAL_ID + "\" }");

        assertThatThrownBy(() -> workflowService.createWorkflow(userId, request))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode().getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST));

        // 거부는 저장 경로에 닿기 전에 끝나야 한다 — 새 버전이 남으면 막은 의미가 없다.
        verifyNoInteractions(workflowCrudService);
    }

    @Test
    @DisplayName("수정 경로에도 같은 검사가 걸린다")
    void rejectsForeignCredentialIdOnUpdate() {
        given(credentialService.getByUserId(userId)).willReturn(List.of(ownedCredential));

        UpdateWorkflowRequest request = read(UpdateWorkflowRequest.class,
            "{ \"credentialId\": \"" + FOREIGN_CREDENTIAL_ID + "\" }");

        assertThatThrownBy(() -> workflowService.updateWorkflow(userId, workflowId, request))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode().getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoSave();
    }

    @Test
    @DisplayName("거부 메시지에 credentialId를 되싣지 않는다 — 메시지가 그대로 WARN 로그에 남는다")
    void rejectionMessageDoesNotEchoCredentialId() {
        given(credentialService.getByUserId(userId)).willReturn(List.of(ownedCredential));

        CreateWorkflowRequest request = read(CreateWorkflowRequest.class,
            "{ \"credentialId\": \"" + FOREIGN_CREDENTIAL_ID + "\" }");

        assertThatThrownBy(() -> workflowService.createWorkflow(userId, request))
            .hasMessageContaining("credentialId")
            .hasMessageNotContaining(FOREIGN_CREDENTIAL_ID.toString())
            .hasMessageNotContaining(OWNED_CREDENTIAL_ID.toString());
    }

    @Test
    @DisplayName("자기 credentialId는 통과한다")
    void allowsOwnCredentialId() {
        stubSaveSucceeds();
        given(credentialService.getByUserId(userId)).willReturn(List.of(ownedCredential));

        CreateWorkflowRequest request = read(CreateWorkflowRequest.class,
            "{ \"credentialId\": \"" + OWNED_CREDENTIAL_ID + "\" }");

        assertThatCode(() -> workflowService.createWorkflow(userId, request))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("credentialId가 없는 노드는 검사 대상이 아니다")
    void ignoresNodeWithoutCredentialId() {
        stubSaveSucceeds();
        CreateWorkflowRequest request = read(CreateWorkflowRequest.class,
            "{ \"model\": \"gemini-3.5-flash\" }");

        assertThatCode(() -> workflowService.createWorkflow(userId, request))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("credentialId가 빈 문자열이면 검사 대상이 아니다 — agent가 비워 보내는 값이다")
    void ignoresBlankCredentialId() {
        stubSaveSucceeds();
        CreateWorkflowRequest request = read(CreateWorkflowRequest.class,
            "{ \"credentialId\": \"\" }");

        assertThatCode(() -> workflowService.createWorkflow(userId, request))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("소유 목록 조회는 노드 수와 무관하게 저장당 한 번이다")
    void looksUpOwnedCredentialsOncePerSave() {
        stubSaveSucceeds();
        given(credentialService.getByUserId(userId)).willReturn(List.of(ownedCredential));

        String body = """
            {
              "name": "AI 워크플로우",
              "nodes": [
                { "id": "n1", "type": "AI", "position": { "x": 0, "y": 0 },
                  "config": { "credentialId": "%s" } },
                { "id": "n2", "type": "AI", "position": { "x": 0, "y": 80 },
                  "config": { "credentialId": "%s" } },
                { "id": "n3", "type": "AI", "position": { "x": 0, "y": 160 },
                  "config": { "credentialId": "%s" } }
              ],
              "edges": []
            }""".formatted(OWNED_CREDENTIAL_ID, OWNED_CREDENTIAL_ID, OWNED_CREDENTIAL_ID);
        CreateWorkflowRequest request;
        try {
            request = jsonMapper.readValue(body, CreateWorkflowRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        workflowService.createWorkflow(userId, request);

        // 노드마다 조회하면 N+1이 된다. 사용자당 최대 10개(MAX_CREDENTIALS_PER_USER)라 한 번이면 충분하다.
        verify(credentialService, times(1)).getByUserId(userId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"apiKey", "api_key", "apikey", "APIKEY", "accessToken", "access_token",
        "secret", "password", "privateKey"})
    @DisplayName("config 최상위에 비밀 원문 키가 있으면 거부한다")
    void rejectsInlineSecretKeys(String secretKey) {
        CreateWorkflowRequest request = read(CreateWorkflowRequest.class,
            "{ \"" + secretKey + "\": \"sk-live-not-a-real-key\" }");

        assertThatThrownBy(() -> workflowService.createWorkflow(userId, request))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode().getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST))
            // 거부 메시지는 그대로 WARN 로그에 남는다 — 막으려던 원문을 로그로 흘리면 안 된다.
            .hasMessageNotContaining("sk-live-not-a-real-key");

        verifyNoInteractions(workflowCrudService);
    }

    @Test
    @DisplayName("수정 경로에도 비밀 원문 키 검사가 걸린다")
    void rejectsInlineSecretKeysOnUpdate() {
        UpdateWorkflowRequest request = read(UpdateWorkflowRequest.class,
            "{ \"apiKey\": \"sk-live-not-a-real-key\" }");

        assertThatThrownBy(() -> workflowService.updateWorkflow(userId, workflowId, request))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode().getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoSave();
    }

    @Test
    @DisplayName("변수 참조는 저장 시점의 비밀이 아니므로 통과한다")
    void allowsVariableReferenceInSecretKey() {
        stubSaveSucceeds();
        CreateWorkflowRequest request = read(CreateWorkflowRequest.class,
            "{ \"apiKey\": \"{{nodes.abc.output.token}}\" }");

        assertThatCode(() -> workflowService.createWorkflow(userId, request))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("참조용 ID 키는 비밀이 아니므로 통과한다")
    void allowsReferenceIdKeys() {
        stubSaveSucceeds();
        given(credentialService.getByUserId(userId)).willReturn(List.of(ownedCredential));

        CreateWorkflowRequest request = read(CreateWorkflowRequest.class, """
            { "credentialId": "%s",
              "webhookCredentialId": "%s",
              "catalogId": "%s" }""".formatted(
            OWNED_CREDENTIAL_ID, UUID.randomUUID(), UUID.randomUUID()));

        assertThatCode(() -> workflowService.createWorkflow(userId, request))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("중첩 Map 안의 비밀 키는 보지 않는다 — HTTP 노드 headers의 Authorization은 정당하다")
    void ignoresSecretKeyNestedInMap() {
        stubSaveSucceeds();
        CreateWorkflowRequest request = read(CreateWorkflowRequest.class,
            "{ \"headers\": { \"apiKey\": \"sk-live-not-a-real-key\" } }");

        assertThatCode(() -> workflowService.createWorkflow(userId, request))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("tools[].credentialId(평면 형태)가 남의 것이면 거부한다")
    void rejectsForeignCredentialIdInFlatToolAuth() {
        given(credentialService.getByUserId(userId)).willReturn(List.of(ownedCredential));

        CreateWorkflowRequest request = read(CreateWorkflowRequest.class, """
            { "tools": [ { "name": "notion_search", "credentialId": "%s" } ] }"""
            .formatted(FOREIGN_CREDENTIAL_ID));

        assertThatThrownBy(() -> workflowService.createWorkflow(userId, request))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode().getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageNotContaining(FOREIGN_CREDENTIAL_ID.toString());

        verifyNoInteractions(workflowCrudService);
    }

    @Test
    @DisplayName("tools[].auth.credentialId(구조화 형태)가 남의 것이면 거부한다")
    void rejectsForeignCredentialIdInStructuredToolAuth() {
        given(credentialService.getByUserId(userId)).willReturn(List.of(ownedCredential));

        CreateWorkflowRequest request = read(CreateWorkflowRequest.class, """
            { "tools": [ { "name": "notion_search",
                "auth": { "type": "credential", "credentialId": "%s" } } ] }"""
            .formatted(FOREIGN_CREDENTIAL_ID));

        assertThatThrownBy(() -> workflowService.createWorkflow(userId, request))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode().getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(workflowCrudService);
    }

    @Test
    @DisplayName("본인 credentialId를 쓰는 도구는 통과한다")
    void allowsOwnCredentialIdInToolAuth() {
        stubSaveSucceeds();
        given(credentialService.getByUserId(userId)).willReturn(List.of(ownedCredential));

        CreateWorkflowRequest request = read(CreateWorkflowRequest.class, """
            { "tools": [ { "name": "notion_search", "credentialId": "%s" },
                         { "name": "slack_post",
                           "auth": { "type": "credential", "credentialId": "%s" } } ] }"""
            .formatted(OWNED_CREDENTIAL_ID, OWNED_CREDENTIAL_ID));

        assertThatCode(() -> workflowService.createWorkflow(userId, request))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("auth.type이 secret이면 auth.value의 원문은 막지 않는다 — 지원되는 기존 기능이다")
    void allowsPlainSecretInToolAuthValue() {
        stubSaveSucceeds();
        CreateWorkflowRequest request = read(CreateWorkflowRequest.class, """
            { "tools": [ { "name": "notion_search",
                "auth": { "type": "secret", "value": "raw-token-value" } } ] }""");

        assertThatCode(() -> workflowService.createWorkflow(userId, request))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("tools가 List가 아니거나 원소가 Map이 아니어도 형변환 예외를 내지 않는다")
    void toleratesMalformedToolsShape() {
        stubSaveSucceeds();
        CreateWorkflowRequest request = read(CreateWorkflowRequest.class,
            "{ \"tools\": \"notion_search\" }");

        assertThatCode(() -> workflowService.createWorkflow(userId, request))
            .doesNotThrowAnyException();

        CreateWorkflowRequest listOfStrings = read(CreateWorkflowRequest.class,
            "{ \"tools\": [ \"notion_search\", null ] }");

        assertThatCode(() -> workflowService.createWorkflow(userId, listOfStrings))
            .doesNotThrowAnyException();
    }
}
