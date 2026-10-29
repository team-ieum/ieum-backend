package com.ieum.workflowcore.engine.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.common.exception.ErrorCode;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** agent {@code POST /v1/workflows/validate} 계약과 fail-open(실패는 전부 Optional.empty)을 고정한다. */
class WorkflowValidationClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Map<String, Object>> nodes = List.of(
        Map.of("id", "t", "type", "TRIGGER", "event", "manual.run", "config", Map.of()),
        Map.of("id", "a", "type", "TRANSFORM", "config", Map.of()));
    private final List<Map<String, Object>> edges = List.of(Map.of("source", "t", "target", "a"));

    private MockWebServer server;
    private WorkflowValidationClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new WorkflowValidationClient(server.url("/").toString(), "internal-secret");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
            .setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    @DisplayName("정의 원본 nodes·edges를 POST /v1/workflows/validate로 보낸다")
    void postsDefinitionToValidatePath() throws Exception {
        server.enqueue(json(200, "{\"valid\":true,\"errors\":[]}"));

        client.validate(nodes, edges);

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/v1/workflows/validate");
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("nodes").get(1).get("id").asText()).isEqualTo("a");
        assertThat(body.get("edges").get(0).get("target").asText()).isEqualTo("a");
    }

    @Test
    @DisplayName("검증 호출에 X-Internal-Token을 싣는다 (IEUM-BE-106)")
    void sendsInternalToken() throws Exception {
        server.enqueue(json(200, "{\"valid\":true,\"errors\":[]}"));

        client.validate(nodes, edges);

        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getHeader("X-Internal-Token")).isEqualTo("internal-secret");
    }

    @Test
    @DisplayName("null nodes·edges는 빈 배열로 보낸다(정의 문서가 비어 있어도 호출이 깨지지 않는다)")
    void nullDefinition_sendsEmptyArrays() throws Exception {
        server.enqueue(json(200, "{\"valid\":false,\"errors\":[]}"));

        client.validate(null, null);

        JsonNode body = mapper.readTree(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertThat(body.get("nodes").isArray()).isTrue();
        assertThat(body.get("edges").isArray()).isTrue();
    }

    @Test
    @DisplayName("에러 목록을 {nodeId, field, code, message}로 읽는다 — nodeId·field null 허용")
    void parsesErrors() {
        server.enqueue(json(200, """
            {"valid":false,"errors":[
              {"nodeId":"a","field":"config.mappings","code":"REQUIRED","message":"'매핑'을 입력하세요."},
              {"nodeId":null,"field":null,"code":"TRIGGER_COUNT","message":"트리거는 하나여야 합니다.","extra":1}
            ]}"""));

        Optional<WorkflowValidationResult> result = client.validate(nodes, edges);

        assertThat(result).isPresent();
        assertThat(result.get().hasErrors()).isTrue();
        assertThat(result.get().errors()).containsExactly(
            new WorkflowValidationResult.ValidationError("a", "config.mappings", "REQUIRED", "'매핑'을 입력하세요."),
            new WorkflowValidationResult.ValidationError(null, null, "TRIGGER_COUNT", "트리거는 하나여야 합니다."));
    }

    @Test
    @DisplayName("valid=false인데 errors가 없으면 차단 대상이 아니다(차단 기준은 errors 목록)")
    void validFalseWithoutErrors_isNotBlocking() {
        server.enqueue(json(200, "{\"valid\":false}"));

        Optional<WorkflowValidationResult> result = client.validate(nodes, edges);

        assertThat(result).isPresent();
        assertThat(result.get().errors()).isEmpty();
        assertThat(result.get().hasErrors()).isFalse();
    }

    @Test
    @DisplayName("agent 5xx → Optional.empty (fail-open)")
    void serverError_returnsEmpty() {
        server.enqueue(json(500, "{\"detail\":\"boom\"}"));

        assertThat(client.validate(nodes, edges)).isEmpty();
    }

    @Test
    @DisplayName("agent 404(엔드포인트 없는 옛 배포) → Optional.empty (fail-open)")
    void notFound_returnsEmpty() {
        server.enqueue(json(404, "{\"detail\":\"Not Found\"}"));

        assertThat(client.validate(nodes, edges)).isEmpty();
    }

    @Test
    @DisplayName("파싱할 수 없는 본문 → Optional.empty (fail-open)")
    void unparseableBody_returnsEmpty() {
        server.enqueue(json(200, "<html>bad gateway</html>"));

        assertThat(client.validate(nodes, edges)).isEmpty();
    }

    @Test
    @DisplayName("빈 본문 → Optional.empty (fail-open)")
    void emptyBody_returnsEmpty() {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json"));

        assertThat(client.validate(nodes, edges)).isEmpty();
    }

    @Test
    @DisplayName("연결 거부 → Optional.empty (fail-open)")
    void connectionRefused_returnsEmpty() throws IOException {
        MockWebServer dead = new MockWebServer();
        dead.start();
        String url = dead.url("/").toString();
        dead.shutdown();

        assertThat(new WorkflowValidationClient(url, "").validate(nodes, edges)).isEmpty();
    }

    @Test
    @DisplayName("3초 안에 응답이 없으면 포기하고 Optional.empty — 5초 지연 응답을 기다리지 않는다")
    void slowAgent_givesUpWithinTimeout() {
        server.enqueue(json(200, "{\"valid\":true,\"errors\":[]}").setHeadersDelay(5, TimeUnit.SECONDS));

        long start = System.nanoTime();
        Optional<WorkflowValidationResult> result = client.validate(nodes, edges);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result).isEmpty();
        assertThat(elapsed).isBetween(Duration.ofMillis(2900), Duration.ofMillis(3900)); // 계약 3초 — 4초로 늘려도 잡힌다
    }

    @Test
    @DisplayName("WorkflowValidationException: 400 코드 + '검증 실패 N건: <첫 message>' + 목록 보존")
    void exceptionSummary() {
        List<WorkflowValidationResult.ValidationError> errors = List.of(
            new WorkflowValidationResult.ValidationError("a", "config.mappings", "REQUIRED", "'매핑'을 입력하세요."),
            new WorkflowValidationResult.ValidationError("b", null, "REF_UNKNOWN_NODE", "없는 노드를 참조합니다."));

        WorkflowValidationException e = new WorkflowValidationException(errors);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_VALIDATION_FAILED);
        assertThat(e.getErrorCode().getStatus().value()).isEqualTo(400);
        assertThat(e.getMessage()).isEqualTo("검증 실패 2건: '매핑'을 입력하세요.");
        assertThat(e.getErrors()).isEqualTo(errors);
    }
}
