package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
 * 노드 config.url에 담긴 Slack·Discord 웹훅 URL 원문 저장을 생성·수정 양쪽에서 거부하는지 본다
 * (IEUM-BE-62).
 *
 * <p>{@code NodeDto.config}는 {@code Map<String, Object>}라 Bean Validation으로는 막을 수 없어
 * 검증이 {@link WorkflowService}에 있다. 그래서 MockMvc가 아니라 서비스 단위로 확인하며, 400은
 * {@link ErrorCode#INVALID_WORKFLOW}의 상태 코드로 검증한다({@code GlobalExceptionHandler}가
 * ErrorCode의 상태를 그대로 응답 상태로 쓴다).
 */
class WorkflowWebhookUrlRejectionTest {

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

    private static final String SLACK_WEBHOOK =
        "https://hooks.slack.com/services/FAKE-WORKSPACE/FAKE-CHANNEL/not-a-real-token";
    private static final String DISCORD_WEBHOOK =
        "https://discord.com/api/webhooks/1234567890/abcdefghijklmnopqrstuvwxyz";

    private CreateWorkflowRequest createRequest(String httpConfig) {
        return read(CreateWorkflowRequest.class, httpConfig);
    }

    private UpdateWorkflowRequest updateRequest(String httpConfig) {
        return read(UpdateWorkflowRequest.class, httpConfig);
    }

    /** HTTP 노드 하나짜리 최소 요청. config만 테스트마다 바꾼다. */
    private <T> T read(Class<T> type, String httpConfig) {
        String body = """
            {
              "name": "웹훅 워크플로우",
              "description": "웹훅 저장 거부 검증",
              "nodes": [
                { "id": "node-http", "type": "HTTP", "label": "알림 보내기",
                  "description": "채널로 알림을 보내요.",
                  "position": { "x": 40, "y": 120 },
                  "config": %s }
              ],
              "edges": []
            }""".formatted(httpConfig);
        try {
            return jsonMapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    @ParameterizedTest(name = "생성 요청의 config.url이 웹훅이면 거부: {0}")
    @ValueSource(strings = {
        SLACK_WEBHOOK,
        DISCORD_WEBHOOK,
        "https://discordapp.com/api/webhooks/1234567890/abcdefghij",
        "https://canary.discord.com/api/webhooks/1234567890/abcdefghij"
    })
    void create_withRawWebhookUrl_rejected(String url) {
        CreateWorkflowRequest request = createRequest(
            "{ \"method\": \"POST\", \"url\": \"" + url + "\" }");

        assertThatThrownBy(() -> workflowService.createWorkflow(userId, request))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode().getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST));

        // 거부는 저장 경로에 닿기 전에 끝나야 한다 — 새 버전이 남으면 막은 의미가 없다.
        verifyNoInteractions(workflowCrudService);
    }

    @Test
    @DisplayName("수정 요청의 config.url이 웹훅이면 거부 — 레거시 워크플로우도 예외 없이 막힌다")
    void update_withRawWebhookUrl_rejected() {
        UpdateWorkflowRequest request = updateRequest(
            "{ \"method\": \"POST\", \"url\": \"" + DISCORD_WEBHOOK + "\" }");

        assertThatThrownBy(() -> workflowService.updateWorkflow(userId, workflowId, request))
            .isInstanceOf(CustomException.class)
            .satisfies(e -> assertThat(((CustomException) e).getErrorCode().getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST));

        // verifyNoInteractions는 쓸 수 없다 — 수정은 병합할 직전 정의를 읽기 전에 소유권부터 검증하므로
        // 거부돼도 crud 호출이 0은 아니다. 확인할 불변식은 "새 버전이 남지 않는다"다 (IEUM-BE-65).
        verify(workflowCrudService, never()).updateWorkflow(
            any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("거부 메시지는 대안을 알려주고 사용자가 보낸 URL은 담지 않는다")
    void rejectionMessage_guidesToCredential_withoutEchoingUrl() {
        CreateWorkflowRequest request = createRequest(
            "{ \"method\": \"POST\", \"url\": \"" + SLACK_WEBHOOK + "\" }");

        assertThatThrownBy(() -> workflowService.createWorkflow(userId, request))
            .isInstanceOf(CustomException.class)
            .hasMessageContaining("webhookCredentialId")
            .hasMessageContaining("웹훅 자격증명")
            // 메시지는 GlobalExceptionHandler가 WARN 로그에도 그대로 남긴다.
            .hasMessageNotContaining(SLACK_WEBHOOK)
            .hasMessageNotContaining("hooks.slack.com");
    }

    @Test
    @DisplayName("웹훅이 아닌 평범한 URL은 통과한다")
    void plainUrl_passes() {
        stubSaveSucceeds();
        CreateWorkflowRequest request = createRequest(
            "{ \"method\": \"POST\", \"url\": \"https://example.com/hook\" }");

        assertThatCode(() -> workflowService.createWorkflow(userId, request))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("webhookCredentialId로 참조하는 노드는 통과한다 — 이게 안내하는 대안이다")
    void webhookCredentialReference_passes() {
        stubSaveSucceeds();
        CreateWorkflowRequest request = createRequest(
            "{ \"method\": \"POST\", \"webhookCredentialId\": \"" + UUID.randomUUID() + "\" }");

        assertThatCode(() -> workflowService.createWorkflow(userId, request))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("url 없는 노드(config 비어 있음)도 통과한다")
    void nodeWithoutUrl_passes() {
        stubSaveSucceeds();
        CreateWorkflowRequest request = createRequest("{}");

        assertThatCode(() -> workflowService.createWorkflow(userId, request))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("url이 문자열이 아니면(숫자 등) 검사에서 걸리지 않고 통과한다")
    void nonStringUrl_passes() {
        stubSaveSucceeds();
        CreateWorkflowRequest request = createRequest("{ \"url\": 42 }");

        assertThatCode(() -> workflowService.createWorkflow(userId, request))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("웹훅 호스트만 있고 토큰 경로가 없으면 통과한다 — 마스킹 규칙과 같은 판정")
    void webhookHostWithoutToken_passes() {
        stubSaveSucceeds();
        CreateWorkflowRequest request = createRequest(
            "{ \"url\": \"https://hooks.slack.com/services/\" }");

        assertThatCode(() -> workflowService.createWorkflow(userId, request))
            .doesNotThrowAnyException();
    }
}
