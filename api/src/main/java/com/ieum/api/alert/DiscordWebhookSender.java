package com.ieum.api.alert;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Discord Incoming Webhook 발신만 담당한다.
 *
 * <p>공유 {@code RestTemplate} 빈을 쓴다 — {@code RestTemplateConfig}에 connect 10s / read 30s
 * 타임아웃이 설정돼 있어 응답 없는 엔드포인트가 호출 스레드를 무한 점유하지 않는다. 이 발신은
 * 실행 런타임의 메인 스레드({@code workflowExecutor} 풀)에서 동기 호출되므로 타임아웃이 없으면
 * 풀 슬롯이 마르고 잡 소비가 멈춘다 — 타임아웃 없는 클라이언트로 바꾸지 말 것.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiscordWebhookSender {

    private final RestTemplate restTemplate;

    /**
     * webhook URL로 메시지를 보낸다. 실패는 예외를 던지지 않고 false로 돌려준다 —
     * 알림 발신 실패가 호출부(실행 종료 처리)를 깨면 안 된다.
     *
     * <p>로그에 webhook URL을 남기지 않는다 — URL 자체가 비밀이다(노출 시 누구나 발송 가능).
     *
     * @return 발신 성공 여부
     */
    public boolean send(String webhookUrl, String content) {
        return sendOnce(webhookUrl, content) == SendResult.SENT;
    }

    /**
     * 한 번 보낸 결과. 다시 보내 볼 만한 실패(429·5xx·읽기 타임아웃 등 IO 오류)는 RETRYABLE, 연결 단계 실패(연결 거부·
     * 연결 타임아웃·DNS)는 UNREACHABLE(재시도 대상이되 Discord 자체가 안 닿는다는 신호), 그 밖의 실패는 PERMANENT.
     */
    public enum SendResult { SENT, RETRYABLE, UNREACHABLE, PERMANENT }

    /** {@link #send}와 같이 보내되 실패를 분류해 돌려준다. 예외를 던지지 않고 URL을 로그에 남기지 않는다. */
    public SendResult sendOnce(String webhookUrl, String content) {
        try {
            // allowed_mentions.parse = [] — 상류 값에 든 @everyone·@here·역할 멘션이 울리지 않게 한다.
            restTemplate.postForEntity(webhookUrl,
                Map.of("content", content, "allowed_mentions", Map.of("parse", List.of())), Void.class);
            return SendResult.SENT;
        } catch (Exception e) {
            log.warn("[DiscordWebhookSender] 발신 실패 — {}", describe(e));
            if (e instanceof HttpStatusCodeException s) {
                return s.getStatusCode().value() == 429 || s.getStatusCode().is5xxServerError()
                    ? SendResult.RETRYABLE : SendResult.PERMANENT;
            }
            if (!(e instanceof ResourceAccessException)) return SendResult.PERMANENT;
            return connectFailure(e) ? SendResult.UNREACHABLE : SendResult.RETRYABLE;
        }
    }

    /**
     * 요청을 보내기 전(연결 단계)에 실패했는지. 이 경우 Discord는 게시하지 않았으니 다시 보내도 중복이 없다.
     * 읽기 타임아웃은 여기 들지 않는다 — Discord가 이미 게시했을 수 있어 재발송하면 중복될 수 있다.
     */
    private static boolean connectFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException
                || t instanceof NoRouteToHostException || t instanceof HttpConnectTimeoutException) {
                return true;
            }
            // JDK HttpURLConnection의 연결 타임아웃은 "Connect timed out" 메시지의 SocketTimeoutException이다
            if (t instanceof SocketTimeoutException && t.getMessage() != null
                && t.getMessage().toLowerCase(Locale.ROOT).contains("connect")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 예외를 로그에 남길 수 있는 형태로 요약한다.
     *
     * <p>{@code e.getMessage()}를 그대로 쓰면 안 된다 — {@code ResourceAccessException}의 메시지는
     * "I/O error on POST request for \"&lt;url&gt;\""처럼 요청 URL을 그대로 담고, webhook URL은
     * 그 자체가 비밀이라 로그로 새면 누구나 발송할 수 있게 된다. 상태 코드까지만 남긴다.
     */
    private String describe(Exception e) {
        if (e instanceof HttpStatusCodeException statusException) {
            return "status: " + statusException.getStatusCode();
        }
        return "cause: " + e.getClass().getSimpleName();
    }
}
