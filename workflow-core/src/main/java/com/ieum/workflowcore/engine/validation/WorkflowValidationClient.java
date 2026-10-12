package com.ieum.workflowcore.engine.validation;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * ieum-agent {@code POST /v1/workflows/validate} 클라이언트 — 워크플로우 정적 검증.
 *
 * <p>런타임(workflow-core)이 쓰므로 api {@code AgentClient}가 아니라 여기서 {@code ${ieum.agent.url}}로 직접 부른다
 * ({@code ActionNodeExecutor}와 같은 이유). 무인증 엔드포인트라 헤더를 싣지 않는다.
 *
 * <p><b>fail-open:</b> 3초 타임아웃·연결 실패·4xx·5xx·빈 본문·파싱 실패는 전부 {@link Optional#empty()}다.
 * 호출자는 empty를 "검증 못 함"으로 보고 실행을 진행한다 — HTTP·조건·변환만 있는 워크플로우가 agent 장애로
 * 멈추면 안 된다. 이 메서드는 예외를 던지지 않는다.
 */
@Slf4j
@Component
public class WorkflowValidationClient {

    private static final String VALIDATE_PATH = "/v1/workflows/validate";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final WebClient webClient;

    public WorkflowValidationClient(@Value("${ieum.agent.url}") String agentBaseUrl) {
        this.webClient = WebClient.builder().baseUrl(agentBaseUrl).build();
    }

    public Optional<WorkflowValidationResult> validate(List<Map<String, Object>> nodes,
                                                       List<Map<String, Object>> edges) {
        Map<String, Object> body = new HashMap<>();
        body.put("nodes", nodes != null ? nodes : List.of());
        body.put("edges", edges != null ? edges : List.of());
        try {
            return Optional.ofNullable(webClient.post()
                .uri(VALIDATE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(WorkflowValidationResult.class)
                .timeout(TIMEOUT)
                .block());
        } catch (Exception e) {
            // 본문(노드 config)은 싣지 않는다. 예외 문자열엔 상태 코드·내부 URL만 있다.
            log.warn("[WorkflowValidation] agent 검증 호출 실패 — 검증 없이 진행한다: {}", e.toString());
            return Optional.empty();
        }
    }
}
