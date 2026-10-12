package com.ieum.api.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.api.webhook.service.HookRequest;
import com.ieum.api.webhook.service.WebhookService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 실제 Tomcat 커넥터를 거친다 — MockMvc는 multipart 리졸버가 본문을 먼저 소비하는 문제를 재현하지 못한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class HookReceiveRealServerTest {

    @LocalServerPort private int port;
    @MockitoBean private WebhookService webhookService;

    @Test
    @DisplayName("multipart 본문도 리졸버에 먹히지 않고 {value: 원문}으로 온다")
    void multipart_isReadAsText() throws Exception {
        String body = "--b\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n--b--\r\n";
        HttpResponse<String> res = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/hooks/tok"))
                .header("Content-Type", "multipart/form-data; boundary=b")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(200);
        ArgumentCaptor<HookRequest> captor = ArgumentCaptor.forClass(HookRequest.class);
        verify(webhookService).receive(eq("tok"), captor.capture());
        assertThat(captor.getValue().output(false)).isEqualTo(Map.of("value", body));
        assertThat(captor.getValue().output(true)).containsEntry("rawBody", body);
    }
}
