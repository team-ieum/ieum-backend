package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.config.RetryProperties;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionContext;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.FailureKind;
import com.ieum.workflowcore.engine.IdempotencyMode;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.engine.RetryPolicy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 노드의 멱등성 가드(Idempotency-Key 헤더 주입, MARKER 차단)와
 * 웹훅 자격증명({@code webhookCredentialId}) 해석 테스트.
 *
 * <p>SSRF 방어({@code validateUrl})가 실제 DNS 조회를 하므로, 테스트 URL 호스트는
 * loopback/private가 아닌 IP 리터럴(TEST-NET-3, 203.0.113.0/24)을 써서 네트워크 호출 없이
 * {@code InetAddress.getByName}이 로컬에서 문자열만 파싱하도록 한다.
 */
class HttpNodeExecutorTest {

    private static final String URL = "http://203.0.113.10/webhook";
    /** 자격증명에서 복호화되어 나오는 비밀 URL — 로그·실패 메시지에 등장하면 안 된다. */
    private static final String RESOLVED_URL = "http://203.0.113.20/services/T000/B000/super-secret-token";

    private RestTemplate restTemplate;
    private IdempotencyStore idempotencyStore;
    private WebhookCredentialProvider webhookCredentialProvider;
    private HttpNodeExecutor executor;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        idempotencyStore = mock(IdempotencyStore.class);
        webhookCredentialProvider = mock(WebhookCredentialProvider.class);
        when(idempotencyStore.markInFlight(any(), any())).thenReturn(true);
        executor = new HttpNodeExecutor(
            restTemplate, new ObjectMapper(), idempotencyStore, webhookCredentialProvider);
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));
    }

    private Node httpNode(Map<String, String> headers) {
        Map<String, Object> config = new HashMap<>();
        config.put("method", "GET");
        config.put("url", URL);
        config.put("headers", headers);
        return new Node("node-1", NodeType.HTTP, "HTTP 노드", config);
    }

    /** config.url은 그대로 두고 webhookCredentialId만 얹은 노드 — 해석된 URL이 우선하는지 본다. */
    private Node webhookNode(Object credentialId) {
        Node node = httpNode(new HashMap<>());
        node.getConfig().put("webhookCredentialId", credentialId);
        return node;
    }

    private ExecutionCursor cursor() {
        return cursor(UUID.randomUUID());
    }

    private ExecutionCursor cursor(UUID userId) {
        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setAllNodes(Collections.emptyList());
        cursor.setAllEdges(Collections.emptyList());
        ExecutionContext context = new ExecutionContext();
        context.setUserId(userId);
        cursor.setContext(context);
        return cursor;
    }

    /** maxAttempts=3, 백오프 없음 — idempotency 모드만 테스트마다 바꾼다. */
    private RetryPolicy policy(IdempotencyMode mode) {
        return new RetryPolicy(3, 0, 1.0, 0, false, null, List.of(), mode);
    }

    @Test
    @DisplayName("HEADER 모드: 재시도 두 회차가 같은 Idempotency-Key를 보낸다")
    void headerMode_sameKeyAcrossAttempts() {
        NodeExecutor.NodeAttempt attempt1 =
            new NodeExecutor.NodeAttempt(1, "fixed-key", policy(IdempotencyMode.HEADER));
        NodeExecutor.NodeAttempt attempt2 =
            new NodeExecutor.NodeAttempt(2, "fixed-key", policy(IdempotencyMode.HEADER));

        executor.execute(httpNode(new HashMap<>()), Collections.emptyMap(), cursor(), attempt1);
        executor.execute(httpNode(new HashMap<>()), Collections.emptyMap(), cursor(), attempt2);

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, times(2))
            .exchange(eq(URL), eq(HttpMethod.GET), captor.capture(), eq(String.class));
        List<HttpEntity> entities = captor.getAllValues();
        assertThat(entities.get(0).getHeaders().getFirst("Idempotency-Key")).isEqualTo("fixed-key");
        assertThat(entities.get(1).getHeaders().getFirst("Idempotency-Key")).isEqualTo("fixed-key");
    }

    @Test
    @DisplayName("사용자가 config.headers에 지정한 Idempotency-Key는 덮어쓰지 않는다")
    void headerMode_userSuppliedHeaderNotOverwritten() {
        Map<String, String> userHeaders = new HashMap<>();
        userHeaders.put("Idempotency-Key", "user-value");
        NodeExecutor.NodeAttempt attempt =
            new NodeExecutor.NodeAttempt(1, "generated-key", policy(IdempotencyMode.HEADER));

        executor.execute(httpNode(userHeaders), Collections.emptyMap(), cursor(), attempt);

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(URL), eq(HttpMethod.GET), captor.capture(), eq(String.class));
        assertThat(captor.getValue().getHeaders().getFirst("Idempotency-Key")).isEqualTo("user-value");
    }

    @Test
    @DisplayName("MARKER 모드: 마커가 이미 있으면 외부 호출 없이 CLIENT_ERROR 실패를 반환한다")
    void markerMode_blockedWhenAlreadyInFlight() {
        RetryPolicy markerPolicy = policy(IdempotencyMode.MARKER);
        when(idempotencyStore.markInFlight("dup-key", NodeExecutor.markerTtl(markerPolicy))).thenReturn(false);
        NodeExecutor.NodeAttempt attempt = new NodeExecutor.NodeAttempt(1, "dup-key", markerPolicy);

        ExecutorResult result =
            executor.execute(httpNode(new HashMap<>()), Collections.emptyMap(), cursor(), attempt);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureKind()).isEqualTo(FailureKind.CLIENT_ERROR);
        verify(restTemplate, never())
            .exchange(any(String.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("NONE 모드(기본값, 3-인자 execute)에서는 헤더도 마커도 개입하지 않는다")
    void noneMode_noHeaderNoMarker() {
        executor.execute(httpNode(new HashMap<>()), Collections.emptyMap(), cursor());

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(URL), eq(HttpMethod.GET), captor.capture(), eq(String.class));
        assertThat(captor.getValue().getHeaders().getFirst("Idempotency-Key")).isNull();
        verify(idempotencyStore, never()).markInFlight(any(), any());
    }

    // ── 실제 RetryPolicy.from() 기본값 경로 (리뷰 I-2) ──────────────────────────
    // 프로덕션은 항상 4-인자 execute를 타므로, 3-인자 NONE 센티넬이 아니라
    // RetryPolicy.from()이 만드는 실제 기본 정책으로 검증해야 한다.

    @Test
    @DisplayName("retry 미선언 HTTP 노드(기본 maxAttempts=1)는 HEADER가 기본값이어도 헤더를 붙이지 않는다")
    void defaultPolicy_retryUndeclared_noHeader() {
        RetryPolicy defaultPolicy = RetryPolicy.from(null, NodeType.HTTP, new RetryProperties());
        NodeExecutor.NodeAttempt attempt = new NodeExecutor.NodeAttempt(1, "generated-key", defaultPolicy);

        executor.execute(httpNode(new HashMap<>()), Collections.emptyMap(), cursor(), attempt);

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(URL), eq(HttpMethod.GET), captor.capture(), eq(String.class));
        assertThat(captor.getValue().getHeaders().getFirst("Idempotency-Key")).isNull();
    }

    @Test
    @DisplayName("retry 선언(maxAttempts>1) HTTP 노드는 HEADER 기본값에 따라 헤더를 붙인다")
    void defaultPolicy_retryDeclared_hasHeader() {
        Map<String, Object> retryConfig = new HashMap<>();
        retryConfig.put("maxAttempts", 3);
        Map<String, Object> nodeConfig = new HashMap<>();
        nodeConfig.put("retry", retryConfig);
        RetryPolicy declaredPolicy = RetryPolicy.from(nodeConfig, NodeType.HTTP, new RetryProperties());
        NodeExecutor.NodeAttempt attempt = new NodeExecutor.NodeAttempt(1, "generated-key", declaredPolicy);

        executor.execute(httpNode(new HashMap<>()), Collections.emptyMap(), cursor(), attempt);

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(URL), eq(HttpMethod.GET), captor.capture(), eq(String.class));
        assertThat(captor.getValue().getHeaders().getFirst("Idempotency-Key")).isEqualTo("generated-key");
    }

    // ── 웹훅 자격증명 해석 (IEUM-BE-62) ────────────────────────────────────────

    @Test
    @DisplayName("webhookCredentialId가 있으면 실행 userId로 해석한 URL로 호출한다")
    void webhookCredential_callsResolvedUrl() {
        UUID userId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        when(webhookCredentialProvider.resolveWebhookUrl(credentialId, userId))
            .thenReturn(Optional.of(RESOLVED_URL));
        when(restTemplate.exchange(eq(RESOLVED_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        ExecutorResult result = executor.execute(
            webhookNode(credentialId.toString()), Collections.emptyMap(), cursor(userId));

        assertThat(result.isSuccess()).isTrue();
        verify(restTemplate)
            .exchange(eq(RESOLVED_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        verify(restTemplate, never())
            .exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("webhookCredentialId가 없으면 기존대로 config.url로 호출하고 자격증명을 조회하지 않는다")
    void noWebhookCredential_usesConfigUrl() {
        ExecutorResult result =
            executor.execute(httpNode(new HashMap<>()), Collections.emptyMap(), cursor());

        assertThat(result.isSuccess()).isTrue();
        verify(restTemplate).exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        verify(webhookCredentialProvider, never()).resolveWebhookUrl(any(), any());
    }

    @Test
    @DisplayName("해석 실패(없는 id·남의 크레덴셜·비활성)는 외부 호출 없이 CLIENT_ERROR로 끝난다")
    void webhookCredential_unresolved_isClientError() {
        UUID userId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        when(webhookCredentialProvider.resolveWebhookUrl(credentialId, userId)).thenReturn(Optional.empty());

        ExecutorResult result = executor.execute(
            webhookNode(credentialId.toString()), Collections.emptyMap(), cursor(userId));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureKind()).isEqualTo(FailureKind.CLIENT_ERROR);
        assertThat(result.getFailureKind().isRetryable()).isFalse();
        assertThat(result.getErrorMessage()).contains(credentialId.toString());
        verify(restTemplate, never())
            .exchange(any(String.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("webhookCredentialId 형식 오류도 CLIENT_ERROR이며 입력값을 메시지에 싣지 않는다")
    void webhookCredential_malformedId_isClientError() {
        ExecutorResult result = executor.execute(
            webhookNode("https://hooks.slack.com/services/T000/B000/leaked"),
            Collections.emptyMap(), cursor());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureKind()).isEqualTo(FailureKind.CLIENT_ERROR);
        assertThat(result.getErrorMessage()).doesNotContain("hooks.slack.com");
        verify(webhookCredentialProvider, never()).resolveWebhookUrl(any(), any());
        verify(restTemplate, never())
            .exchange(any(String.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("userId가 없으면 해석을 시도하지 않고 CLIENT_ERROR로 끝난다")
    void webhookCredential_noUserId_isClientError() {
        UUID credentialId = UUID.randomUUID();

        ExecutorResult result = executor.execute(
            webhookNode(credentialId.toString()), Collections.emptyMap(), cursor(null));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureKind()).isEqualTo(FailureKind.CLIENT_ERROR);
        verify(webhookCredentialProvider, never()).resolveWebhookUrl(any(), any());
    }

    @Test
    @DisplayName("전송 실패 메시지에 해석된 웹훅 URL이 남지 않는다")
    void webhookCredential_transportFailure_messageHasNoUrl() {
        UUID userId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        when(webhookCredentialProvider.resolveWebhookUrl(credentialId, userId))
            .thenReturn(Optional.of(RESOLVED_URL));
        // RestTemplate의 전송 예외 메시지는 요청 URL을 그대로 담는다.
        when(restTemplate.exchange(eq(RESOLVED_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(new ResourceAccessException(
                "I/O error on GET request for \"" + RESOLVED_URL + "\": connect timed out"));

        ExecutorResult result = executor.execute(
            webhookNode(credentialId.toString()), Collections.emptyMap(), cursor(userId));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).doesNotContain(RESOLVED_URL);
        assertThat(result.getErrorMessage()).doesNotContain("super-secret-token");
        assertThat(result.getErrorMessage()).contains("connect timed out");
    }

    @Test
    @DisplayName("URL 검증 실패 메시지에도 해석된 웹훅 URL이 남지 않는다")
    void webhookCredential_invalidUrl_messageHasNoUrl() {
        UUID userId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        // URI 파싱이 실패하는 값 — validateUrl이 URL 원문을 담은 메시지로 예외를 던진다.
        String brokenUrl = "http://203.0.113.20/services/super-secret-token^broken";
        when(webhookCredentialProvider.resolveWebhookUrl(credentialId, userId))
            .thenReturn(Optional.of(brokenUrl));

        ExecutorResult result = executor.execute(
            webhookNode(credentialId.toString()), Collections.emptyMap(), cursor(userId));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureKind()).isEqualTo(FailureKind.CLIENT_ERROR);
        assertThat(result.getErrorMessage()).doesNotContain("super-secret-token");
    }
}
