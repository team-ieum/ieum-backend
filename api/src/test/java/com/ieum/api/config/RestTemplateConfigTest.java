package com.ieum.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.workflowcore.engine.executor.HttpNodeExecutor;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

/** HTTP 노드 전용 클라이언트 — 리다이렉트 미추적·본문 1MB 상한을 실제 HTTP로 본다 (IEUM-BE-106). */
class RestTemplateConfigTest {

    private static final int MAX = HttpNodeExecutor.MAX_RESPONSE_BYTES;

    private MockWebServer server;
    private RestTemplate httpNode;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        httpNode = new RestTemplateConfig().httpNodeRestTemplate(new RestTemplateBuilder(), 5, 5);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private ResponseEntity<String> get(String path) {
        return httpNode.exchange(server.url(path).toString(), HttpMethod.GET, HttpEntity.EMPTY, String.class);
    }

    @Test
    @DisplayName("302를 따라가지 않는다 — 3xx가 그대로 돌아오고 Location 쪽으로 두 번째 요청이 나가지 않는다")
    void redirectNotFollowed() {
        server.enqueue(new MockResponse().setResponseCode(302)
            .setHeader("Location", server.url("/internal-secret").toString()));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("내부 응답"));

        ResponseEntity<String> response = get("/start");

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getLocation()).hasToString(server.url("/internal-secret").toString());
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("1MB를 넘는 본문은 1MB에서 잘린다")
    void largeBodyCapped() {
        server.enqueue(new MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/plain; charset=UTF-8").setBody("a".repeat(MAX + 4096)));

        assertThat(get("/big").getBody()).hasSize(MAX);
    }

    @Test
    @DisplayName("1MB 이하 본문은 그대로")
    void smallBodyUnchanged() {
        server.enqueue(new MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "application/json").setBody("{\"ok\":true}"));

        assertThat(get("/small").getBody()).isEqualTo("{\"ok\":true}");
    }

    @Test
    @DisplayName("오류 응답 본문도 1MB에서 잘린다 — 예외 메시지용으로 통째로 읽지 않는다")
    void errorBodyCapped() {
        server.enqueue(new MockResponse().setResponseCode(500)
            .setHeader("Content-Type", "text/plain; charset=UTF-8").setBody("e".repeat(MAX + 4096)));

        assertThatThrownBy(() -> get("/error"))
            .isInstanceOfSatisfying(HttpServerErrorException.class, e ->
                assertThat(e.getResponseBodyAsByteArray()).hasSize(MAX));
    }
}
