package com.ieum.api.alert;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
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
        try {
            restTemplate.postForEntity(webhookUrl, Map.of("content", content), Void.class);
            return true;
        } catch (Exception e) {
            log.warn("[DiscordWebhookSender] 발신 실패 — {}", describe(e));
            return false;
        }
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
