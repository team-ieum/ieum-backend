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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 노드의 멱등성 가드(Idempotency-Key 헤더 주입, MARKER 차단) 테스트.
 *
 * <p>SSRF 방어({@code validateUrl})가 실제 DNS 조회를 하므로, 테스트 URL 호스트는
 * loopback/private가 아닌 IP 리터럴(TEST-NET-3, 203.0.113.0/24)을 써서 네트워크 호출 없이
 * {@code InetAddress.getByName}이 로컬에서 문자열만 파싱하도록 한다.
 */
class HttpNodeExecutorTest {

    private static final String URL = "http://203.0.113.10/webhook";

    private RestTemplate restTemplate;
    private IdempotencyStore idempotencyStore;
    private HttpNodeExecutor executor;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        idempotencyStore = mock(IdempotencyStore.class);
        when(idempotencyStore.markInFlight(any(), any())).thenReturn(true);
        executor = new HttpNodeExecutor(restTemplate, new ObjectMapper(), idempotencyStore);
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

    private ExecutionCursor cursor() {
        ExecutionCursor cursor = new ExecutionCursor();
        cursor.setAllNodes(Collections.emptyList());
        cursor.setAllEdges(Collections.emptyList());
        cursor.setContext(new ExecutionContext());
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
}
