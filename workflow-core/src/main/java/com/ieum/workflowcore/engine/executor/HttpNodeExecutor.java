package com.ieum.workflowcore.engine.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.engine.ExecutionCursor;
import com.ieum.workflowcore.engine.ExecutorResult;
import com.ieum.workflowcore.engine.Node;
import java.net.InetAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
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

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public NodeType getNodeType() {
        return NodeType.HTTP;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ExecutorResult execute(Node node, Map<String, Object> input, ExecutionCursor cursor) {
        long startTime = System.currentTimeMillis();
        log.info("[HttpExecutor] 노드 실행 — nodeId: {}", node.getId());

        try {
            Map<String, Object> config = node.getConfig();
            String method = ((String) config.getOrDefault("method", "GET")).toUpperCase();
            String url = cursor.renderVariables((String) config.get("url"));
            Map<String, String> rawHeaders = (Map<String, String>) config.getOrDefault("headers", new HashMap<>());
            Object bodyObj = config.get("body");

            log.debug("[HttpExecutor] {} {}", method, url);
            validateUrl(url);

            // 헤더 변수 치환
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            rawHeaders.forEach((k, v) -> httpHeaders.set(k, cursor.renderVariables(v)));

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
                "HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(),
                System.currentTimeMillis() - startTime
            );
        } catch (Exception e) {
            log.error("[HttpExecutor] 실행 실패 — nodeId: {}", node.getId(), e);
            return ExecutorResult.failure(e.getMessage(), System.currentTimeMillis() - startTime);
        }
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
