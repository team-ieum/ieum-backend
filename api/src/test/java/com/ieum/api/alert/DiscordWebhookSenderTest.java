package com.ieum.api.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

class DiscordWebhookSenderTest {

    /** 이 URL의 마지막 경로 세그먼트가 비밀이다 — 로그에 나타나면 안 된다. */
    private static final String URL = "https://discord.com/api/webhooks/123/superSecretToken";

    private RestTemplate restTemplate;
    private DiscordWebhookSender sender;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        restTemplate = Mockito.mock(RestTemplate.class);
        sender = new DiscordWebhookSender(restTemplate);

        appender = new ListAppender<>();
        appender.start();
        logger = (Logger) LoggerFactory.getLogger(DiscordWebhookSender.class);
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private String logOutput() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    @DisplayName("content 필드 하나만 담은 페이로드를 POST한다")
    void send_postsContentPayload() {
        assertThat(sender.send(URL, "실패 알림")).isTrue();

        verify(restTemplate).postForEntity(eq(URL), eq(Map.of("content", "실패 알림")), eq(Void.class));
    }

    @Test
    @DisplayName("I/O 오류 로그에 webhook URL이 새지 않는다 — 예외 메시지가 URL을 담고 있어도")
    void send_ioError_doesNotLeakUrl() {
        // ResourceAccessException의 메시지는 요청 URL을 그대로 담는다.
        when(restTemplate.postForEntity(eq(URL), any(), eq(Void.class)))
                .thenThrow(new ResourceAccessException("I/O error on POST request for \"" + URL + "\""));

        assertThat(sender.send(URL, "실패 알림")).isFalse();

        assertThat(logOutput())
                .doesNotContain("superSecretToken")
                .doesNotContain(URL)
                .contains("ResourceAccessException");
    }

    @Test
    @DisplayName("HTTP 오류는 상태 코드만 로그에 남긴다")
    void send_httpError_logsStatusOnly() {
        when(restTemplate.postForEntity(eq(URL), any(), eq(Void.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        assertThat(sender.send(URL, "실패 알림")).isFalse();

        assertThat(logOutput())
                .doesNotContain("superSecretToken")
                .contains("404");
    }
}
