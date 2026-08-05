package com.ieum.workflowcore.engine.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.FailureClassifier;
import com.ieum.workflowcore.engine.FailureKind;
import com.ieum.workflowcore.engine.Node;
import com.ieum.workflowcore.engine.RetryPolicy;
import java.net.InetAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 노드 Executor.
 *
 * <p>config의 {@code method}, {@code url}, {@code headers}, {@code body}를
 * 변수 치환한 뒤 외부 HTTP API를 호출하고 응답을 output으로 반환한다.
 *
 * <p>config에 {@code webhookCredentialId}가 있으면 {@code url} 대신
 * {@link WebhookCredentialProvider}가 복호화한 Slack/Discord 웹훅 URL로 호출한다.
 * 웹훅 URL은 그 자체가 비밀이므로 노드 config에 저장하지 않고 실행 시점에만 해석하며,
 * 해석된 URL은 로그·실패 메시지에 남기지 않는다.
 *
 * <p>지원 메서드: GET, POST, PUT, DELETE
 *
 * <p>output 형식:
 * <pre>{@code
 * {
 *   "statusCode": 200,
 *   "body": { ... }   // JSON이면 Map, 아니면 String
 * }
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpNodeExecutor implements NodeExecutor {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final IdempotencyStore idempotencyStore;
    private final WebhookCredentialProvider webhookCredentialProvider;

    @Override
    public NodeType getNodeType() {
        return NodeType.HTTP;
    }

    @Override
    public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
        return execute(node, input, cursor, NodeAttempt.NONE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor, NodeAttempt attempt) {
        long startTime = System.currentTimeMillis();
        log.info("[HttpExecutor] 노드 실행 — nodeId: {}", node.getId());
        RetryPolicy policy = attempt.policy();

        // 웹훅 자격증명에서 해석한 URL. 이 값이 채워지면 로그·실패 메시지에서 가린다(redact).
        String secretUrl = null;

        try {
            Map<String, Object> config = node.getConfig();
            String method = ((String) config.getOrDefault("method", "GET")).toUpperCase();
            Map<String, String> rawHeaders = (Map<String, String>) config.getOrDefault("headers", new HashMap<>());
            Object bodyObj = config.get("body");

            Object rawCredentialId = config.get("webhookCredentialId");
            String url;
            if (rawCredentialId != null && !rawCredentialId.toString().isBlank()) {
                UUID webhookCredentialId;
                try {
                    webhookCredentialId = UUID.fromString(rawCredentialId.toString().trim());
                } catch (IllegalArgumentException e) {
                    // 형식 오류. 값 자체는 메시지에 싣지 않는다 — 사용자가 여기에 URL 원문을 넣었을 수 있다.
                    log.warn("[HttpExecutor] webhookCredentialId 형식 오류 — nodeId: {}", node.getId());
                    return ExecutorResult.failure(
                        "webhookCredentialId 형식이 올바르지 않습니다.",
                        System.currentTimeMillis() - startTime, FailureKind.CLIENT_ERROR);
                }
                UUID userId = cursor.getContext().getUserId();
                String resolved = userId == null ? null
                    : webhookCredentialProvider.resolveWebhookUrl(webhookCredentialId, userId).orElse(null);
                if (resolved == null) {
                    // 없는 id·남의 크레덴셜·비활성 — 어느 쪽이든 재시도로 풀리지 않는다.
                    log.warn("[HttpExecutor] 웹훅 자격증명 미해결 — nodeId: {}, webhookCredentialId: {}",
                        node.getId(), webhookCredentialId);
                    return ExecutorResult.failure(
                        "웹훅 자격증명을 사용할 수 없습니다 — webhookCredentialId: " + webhookCredentialId,
                        System.currentTimeMillis() - startTime, FailureKind.CLIENT_ERROR);
                }
                url = resolved;
                secretUrl = resolved;
                log.debug("[HttpExecutor] {} — webhookCredentialId: {}", method, webhookCredentialId);
            } else {
                url = cursor.renderVariables((String) config.get("url"));
                log.debug("[HttpExecutor] {} {}", method, url);
            }
            validateUrl(url);

            // 헤더 변수 치환
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            rawHeaders.forEach((k, v) -> httpHeaders.set(k, cursor.renderVariables(v)));

            // 사용자가 config.headers에 이미 Idempotency-Key를 넣었으면 덮어쓰지 않는다.
            // HttpHeaders는 대소문자 구분 없는 맵이라 containsKey가 사용자 표기와 무관하게 감지한다.
            // policy.isDisabled()(재시도 없음)면 헤더를 붙이지 않는다 — HTTP는 HEADER가 기본값이라
            // retry 미선언 노드(maxAttempts=1)까지 헤더가 나가면 기존 워크플로우의 외부 요청이
            // 이번 변경으로 바뀐다. 멱등성 키는 재시도가 있을 때만 의미가 있다(리뷰 I-2).
            if (policy != null && policy.idempotency().usesHeader() && !policy.isDisabled()
                    && !httpHeaders.containsKey(IDEMPOTENCY_HEADER)) {
                httpHeaders.set(IDEMPOTENCY_HEADER, attempt.idempotencyKey());
            }

            if (policy != null && policy.idempotency().usesMarker()) {
                if (!idempotencyStore.markInFlight(attempt.idempotencyKey(), NodeExecutor.markerTtl(policy))) {
                    log.warn("[HttpExecutor] 멱등성 마커 충돌로 호출 차단 — nodeId: {}", node.getId());
                    return ExecutorResult.failure(
                        "중복 호출 차단 — 이전 시도가 외부 서비스에 도달했을 수 있어 재시도를 중단합니다.",
                        System.currentTimeMillis() - startTime, FailureKind.CLIENT_ERROR);
                }
            }

            // body 변수 치환
            String bodyJson = null;
            if (bodyObj != null) {
                String rawBody = objectMapper.writeValueAsString(bodyObj);
                bodyJson = cursor.renderVariables(rawBody);
            }

            ResponseEntity<String> response = sendRequest(method, url, httpHeaders, bodyJson);

            Map<String, Object> output = new HashMap<>();
            output.put("statusCode", response.getStatusCode().value());
            output.put("body", parseBody(response.getBody()));

            log.info("[HttpExecutor] 응답 수신 — status: {}", response.getStatusCode().value());
            return ExecutorResult.success(output, System.currentTimeMillis() - startTime);

        } catch (HttpStatusCodeException e) {
            log.error("[HttpExecutor] HTTP 오류 — nodeId: {}, status: {}", node.getId(), e.getStatusCode());
            return ExecutorResult.failure(
                "HTTP " + e.getStatusCode().value() + ": " + redact(e.getResponseBodyAsString(), secretUrl),
                System.currentTimeMillis() - startTime,
                FailureClassifier.fromHttpStatus(e.getStatusCode().value())
            );
        } catch (Exception e) {
            String message = redact(e.getMessage(), secretUrl);
            if (secretUrl == null) {
                log.error("[HttpExecutor] 실행 실패 — nodeId: {}", node.getId(), e);
            } else {
                // 예외 객체를 넘기면 스택 트레이스에 원본 메시지가 실려 복호된 웹훅 URL이 로그에 남는다.
                // (RestTemplate의 ResourceAccessException 메시지가 요청 URL을 포함한다)
                log.error("[HttpExecutor] 실행 실패 — nodeId: {}, type: {}, 오류: {}",
                    node.getId(), e.getClass().getSimpleName(), message);
            }
            return ExecutorResult.failure(message, System.currentTimeMillis() - startTime,
                FailureClassifier.fromException(e));
        }
    }

    /**
     * 복호된 웹훅 URL이 실패 메시지·로그로 새 나가지 않도록 원문 그대로 등장하는 부분을 가린다.
     * {@code secretUrl}이 null이면(= config.url 경로) 입력을 그대로 돌려준다.
     */
    private String redact(String message, String secretUrl) {
        if (message == null || secretUrl == null) {
            return message;
        }
        return message.replace(secretUrl, "***");
    }

    private ResponseEntity<String> sendRequest(
        String method, String url, HttpHeaders headers, String bodyJson
    ) {
        HttpEntity<String> entity = new HttpEntity<>(bodyJson, headers);
        return switch (method) {
            case "GET" -> restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            case "POST" -> restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            case "PUT" -> restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
            case "DELETE" -> restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
            default -> throw new IllegalArgumentException("지원하지 않는 HTTP 메서드: " + method);
        };
    }

    /**
     * SSRF 방어: 루프백·사이트로컬·링크로컬 등 내부 네트워크 주소 차단.
     * AWS 메타데이터(169.254.169.254), localhost, 10.x/172.16-31.x/192.168.x 등을 포함한다.
     */
    private void validateUrl(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("URL에 호스트가 없습니다: " + url);
            }
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress()
                    || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress()) {
                throw new IllegalArgumentException(
                    "내부 네트워크 주소로의 요청은 허용되지 않습니다: " + host);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("유효하지 않은 URL: " + url, e);
        }
    }

    private Object parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // JSON 파싱 실패 시 문자열로 반환
            return body;
        }
    }
}
