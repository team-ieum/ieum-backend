package com.ieum.api.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.chat.dto.ChatStreamEvent;
import com.ieum.api.chat.service.AgentClient.AgentChatCallParams;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class AgentClientTest {

    private MockWebServer mockWebServer;
    private AgentClient agentClient;
    private final UUID userId = UUID.randomUUID();

    private static final String INTERNAL_TOKEN = "internal-secret";

    private static final String CHAT_RESPONSE_BODY =
        "{\"message\":\"안녕하세요\",\"type\":\"CLARIFICATION_NEEDED\"}";

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        agentClient = new AgentClient(mockWebServer.url("/").toString(), new ObjectMapper(), INTERNAL_TOKEN);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    private AgentChatCallParams.AgentChatCallParamsBuilder baseParams() {
        return AgentChatCallParams.builder()
            .workflowId(UUID.randomUUID())
            .prompt("요약해줘")
            .llmProvider("CLAUDE")
            .userId(userId);
    }

    @Test
    @DisplayName("chatStream — useBetaPlatformKey=true면 X-Key-Mode:platform, X-LLM-Provider:GEMINI, 키 헤더 없음, X-User-Role 실음")
    void chatStream_betaPlatformKey_setsHeadersAndOmitsKey() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("event: done\ndata: " + CHAT_RESPONSE_BODY + "\n\n"));

        agentClient.chatStream(baseParams()
            .userRole("ROLE_USER")
            .useBetaPlatformKey(true)
            .build()
        ).blockLast();

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Key-Mode")).isEqualTo("platform");
        assertThat(recorded.getHeader("X-LLM-Provider")).isEqualTo("GEMINI");
        assertThat(recorded.getHeader("X-LLM-Api-Key")).isNull();
        assertThat(recorded.getHeader("X-User-Role")).isEqualTo("ROLE_USER");
    }

    @Test
    @DisplayName("chatStream — useBetaPlatformKey=false(BYOK)면 키 헤더를 싣고 X-Key-Mode는 없다")
    void chatStream_byok_keepsKeyHeader() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream").setBody("event: done\ndata: " + CHAT_RESPONSE_BODY + "\n\n"));

        agentClient.chatStream(baseParams().apiKey("decrypted-api-key").build()).blockLast();

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-Key-Mode")).isNull();
        assertThat(recorded.getHeader("X-LLM-Provider")).isEqualTo("CLAUDE");
        assertThat(recorded.getHeader("X-LLM-Api-Key")).isEqualTo("decrypted-api-key");
    }

    @Test
    @DisplayName("chatStream 본문은 prompt·노드·엣지만 싣는다 — 연동·MCP·웹훅 목록은 agent가 위임 토큰으로 묻는다")
    void chatStream_bodyCarriesOnlyPromptNodesEdges() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream").setBody("event: done\ndata: " + CHAT_RESPONSE_BODY + "\n\n"));

        agentClient.chatStream(baseParams()
            .currentNodes(List.<Object>of(Map.of("id", "n1", "event", "http.request")))
            .currentEdges(List.<Object>of(Map.of("id", "e1", "source", "n1", "target", "n2")))
            .build()).blockLast();

        JsonNode body = new ObjectMapper().readTree(mockWebServer.takeRequest().getBody().readUtf8());
        assertThat(body.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("workflowId", "prompt", "currentNodes", "currentEdges");
        assertThat(body.path("prompt").asText()).isEqualTo("요약해줘");
        assertThat(body.path("currentNodes").get(0).path("event").asText()).isEqualTo("http.request");
        assertThat(body.path("currentEdges").get(0).path("id").asText()).isEqualTo("e1");
    }

    @Test
    @DisplayName("chatStream·getNodeCatalog 모두 X-Internal-Token을 싣는다 (IEUM-BE-106)")
    void everyAgentCall_carriesInternalToken() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream").setBody("event: done\ndata: " + CHAT_RESPONSE_BODY + "\n\n"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "application/json").setBody("{\"nodes\": []}"));

        agentClient.chatStream(baseParams().build()).blockLast();
        agentClient.getNodeCatalog();

        for (int i = 0; i < 2; i++) {
            assertThat(mockWebServer.takeRequest().getHeader("X-Internal-Token")).isEqualTo(INTERNAL_TOKEN);
        }
    }

    @Test
    @DisplayName("위임 토큰은 X-Delegation-Token으로 싣고, 없으면 헤더도 없다 (IEUM-BE-106)")
    void delegationToken_header() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream").setBody("event: done\ndata: " + CHAT_RESPONSE_BODY + "\n\n"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream").setBody("event: done\ndata: " + CHAT_RESPONSE_BODY + "\n\n"));

        agentClient.chatStream(baseParams().delegationToken("deleg-raw").build()).blockLast();
        agentClient.chatStream(baseParams().build()).blockLast();

        assertThat(mockWebServer.takeRequest().getHeader("X-Delegation-Token")).isEqualTo("deleg-raw");
        assertThat(mockWebServer.takeRequest().getHeader("X-Delegation-Token")).isNull();
    }

    @Test
    @DisplayName("AgentChatCallParams.toString은 위임 토큰을 가린다")
    void callParams_toStringMasksDelegationToken() {
        assertThat(baseParams().delegationToken("deleg-raw").build().toString())
            .doesNotContain("deleg-raw").contains("delegationToken=***");
    }

    @Test
    @DisplayName("턴 시간 상한은 이벤트 사이 간격이 아니라 전체 시간이다 — 300초마다 이벤트가 와도 570초에 끊긴다")
    void withTurnTimeout_isTotalNotPerEvent() {
        StepVerifier.withVirtualTime(() -> AgentClient.withTurnTimeout(
                Flux.interval(Duration.ofSeconds(300)).take(3), Duration.ofSeconds(570)))
            .expectSubscription()
            .thenAwait(Duration.ofSeconds(300))
            .expectNext(0L)
            .thenAwait(Duration.ofSeconds(270))
            .expectError(TimeoutException.class)
            .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("턴 시간 상한 안에 끝나면 그대로 완료된다")
    void withTurnTimeout_completesWithinLimit() {
        StepVerifier.withVirtualTime(() -> AgentClient.withTurnTimeout(
                Flux.interval(Duration.ofSeconds(100)).take(2), Duration.ofSeconds(570)))
            .expectSubscription()
            .thenAwait(Duration.ofSeconds(200))
            .expectNext(0L, 1L)
            .expectComplete()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("chatStream은 agent가 응답하지 않아도 570초 턴 상한에서 error 이벤트로 끝난다")
    void chatStream_appliesTurnTimeout() {
        mockWebServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        StepVerifier.withVirtualTime(() -> agentClient.chatStream(baseParams().build()))
            .expectSubscription()
            .thenAwait(AgentClient.CHAT_STREAM_TIMEOUT)
            .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.EventType.ERROR))
            .expectComplete()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("internal-token이 비면 X-Internal-Token을 싣지 않는다 — agent도 값이 없으면 검사를 끈다")
    void blankInternalToken_headerOmitted() throws InterruptedException {
        AgentClient noToken = new AgentClient(mockWebServer.url("/").toString(), new ObjectMapper(), "");
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "application/json").setBody("{\"nodes\": []}"));

        noToken.getNodeCatalog();

        assertThat(mockWebServer.takeRequest().getHeader("X-Internal-Token")).isNull();
    }

    @Test
    @DisplayName("getNodeCatalog — agent GET /v1/nodes/catalog 응답 JSON을 그대로, 크레덴셜 헤더 없이")
    void getNodeCatalog_returnsAgentBodyAsIs() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"entries\":[{\"id\":\"action.google_sheets_write\",\"inputFields\":"
                + "[{\"key\":\"spreadsheet_id\",\"optionsSource\":\"google.spreadsheets\"}]}]}"));

        JsonNode catalog = agentClient.getNodeCatalog();

        assertThat(catalog.path("entries").get(0).path("inputFields").get(0).path("optionsSource").asText())
            .isEqualTo("google.spreadsheets");
        RecordedRequest recorded = mockWebServer.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("GET");
        assertThat(recorded.getPath()).endsWith("/v1/nodes/catalog");
        assertThat(recorded.getHeader("X-LLM-Api-Key")).isNull();
        assertThat(recorded.getHeader("X-LLM-Provider")).isNull();
        assertThat(recorded.getHeader("X-User-Id")).isNull();
    }

    @Test
    @DisplayName("getNodeCatalog — agent 5xx는 PROVIDER_ERROR")
    void getNodeCatalog_agentErrorMapsLikeChat() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> agentClient.getNodeCatalog())
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.PROVIDER_ERROR);
    }

    @Test
    @DisplayName("getNodeCatalog — agent가 아직 경로를 모르면(404, AI-65 배포 전) PROVIDER_ERROR")
    void getNodeCatalog_agent404MapsToProviderError() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(404));

        assertThatThrownBy(() -> agentClient.getNodeCatalog())
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.PROVIDER_ERROR);
    }

    @Test
    @DisplayName("getNodeCatalog — agent 429는 PROVIDER_RATE_LIMITED")
    void getNodeCatalog_agent429MapsToRateLimited() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(429));

        assertThatThrownBy(() -> agentClient.getNodeCatalog())
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.PROVIDER_RATE_LIMITED);
    }

    @Test
    @DisplayName("getNodeCatalog — 200 빈 본문은 PROVIDER_ERROR")
    void getNodeCatalog_emptyBodyIsProviderError() {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> agentClient.getNodeCatalog())
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.PROVIDER_ERROR);
    }

    @Test
    @DisplayName("agent 422는 PROVIDER_ERROR이고, 로그에 응답 본문(노드 config·prompt가 실린다)을 남기지 않는다")
    void agent422_doesNotLogBody() {
        String secretBody = "{\"detail\":[{\"input\":{\"prompt\":\"secret-prompt-xyz\"}}]}";
        mockWebServer.enqueue(new MockResponse().setResponseCode(422)
            .setHeader("Content-Type", "application/json").setBody(secretBody));
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(AgentClient.class);
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> agentClient.getNodeCatalog())
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROVIDER_ERROR);
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("secret-prompt-xyz"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("스트림 상한 570초는 동시 턴 락 TTL보다 짧다 — 거꾸로면 진행 중인 턴의 락이 먼저 풀린다")
    void chatStreamTimeout_isShorterThanTurnLockTtl() {
        assertThat(AgentClient.CHAT_STREAM_TIMEOUT).isEqualTo(Duration.ofSeconds(570));
        assertThat(AgentClient.CHAT_STREAM_TIMEOUT).isLessThan(ChatTurnLock.TTL);
    }
}
