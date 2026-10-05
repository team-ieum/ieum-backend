package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.engine.executor.NotionTokenProvider;
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
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Notion 공급원들이 공유하는 search 호출 — 토큰, 버전 헤더, 오류 매핑을 한 곳에 둔다.
 *
 * <p>트랜잭션을 걸지 않는다(options 패키지 관례). Notion 토큰은 만료가 없어 갱신하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class NotionApiReader {

    /**
     * agent {@code tools/notion.py}의 {@code _NOTION_VERSION}과 같은 값이어야 한다 — 이 버전의 data source id를
     * 드롭다운이 주고 agent 도구가 그 id로 조회한다(IEUM-AI-61).
     */
    static final String NOTION_VERSION = "2026-03-11";
    static final String UNTITLED = "(제목 없음)";

    private static final URI SEARCH_URI = URI.create("https://api.notion.com/v1/search");

    private final NotionTokenProvider notionTokenProvider;
    private final RestTemplate restTemplate;

    /**
     * 연동 때 공유된 페이지·data source를 최근 편집순 50개씩 검색한다. 검색어는 보내지 않는다.
     *
     * @param objectType {@code page} 또는 {@code data_source}
     * @param cursor 이전 응답의 {@code next_cursor}. 첫 페이지면 null
     */
    JsonNode search(UUID userId, String objectType, String cursor) {
        String token = notionTokenProvider.getAccessToken(userId)
            .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_CONNECTED));

        Map<String, Object> body = new HashMap<>();
        body.put("filter", Map.of("property", "object", "value", objectType));
        body.put("sort", Map.of("timestamp", "last_edited_time", "direction", "descending"));
        body.put("page_size", 50);
        if (cursor != null) {
            body.put("start_cursor", cursor);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Notion-Version", NOTION_VERSION);
        try {
            JsonNode response = restTemplate.exchange(SEARCH_URI, HttpMethod.POST, new HttpEntity<>(body, headers),
                JsonNode.class).getBody();
            return response != null ? response : JsonNodeFactory.instance.objectNode();
        } catch (HttpClientErrorException e) {
            // 본문 message엔 id가 섞일 수 있어 code 필드만 남긴다
            log.warn("[NotionApiReader] Notion 요청 거부 — status: {}, code: {}",
                e.getStatusCode().value(), errorCode(e));
            throw clientError(e.getStatusCode().value());
        } catch (RestClientException e) {
            // 5xx·타임아웃·연결 실패 — 사용자가 고칠 수 없는 일시 장애
            log.warn("[NotionApiReader] Notion 호출 실패 — {}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.NOTION_API_UNAVAILABLE);
        }
    }

    /** {@code has_more}가 true일 때만 다음 커서 — false면 {@code next_cursor}에 값이 있어도 끝이다. */
    static String nextCursor(JsonNode body) {
        return body.path("has_more").asBoolean(false) ? body.path("next_cursor").asText(null) : null;
    }

    /** rich text 배열의 {@code plain_text}를 잇는다. 비거나 공백뿐이면 {@link #UNTITLED}. */
    static String plainTextOrUntitled(JsonNode richText) {
        StringBuilder text = new StringBuilder();
        richText.forEach(part -> text.append(part.path("plain_text").asText("")));
        return text.toString().isBlank() ? UNTITLED : text.toString();
    }

    private static String errorCode(HttpClientErrorException e) {
        try {
            return e.getResponseBodyAs(JsonNode.class).path("code").asText("unknown");
        } catch (RuntimeException ignored) {
            return "unknown"; // 본문 없음·JSON 아님(변환 불가 시 null도 여기로)
        }
    }

    private static CustomException clientError(int status) {
        return switch (status) {
            // 기본 문구가 Google 재연동이라 덮어쓴다
            case 401 -> new CustomException(ErrorCode.AUTHENTICATION_REQUIRED, "Notion 계정을 다시 연동해주세요.");
            // restricted_resource — 연동 때 준 권한(capability·공유 범위)이 부족하다. 사용자가 고칠 길은 재연동이다
            case 403 -> new CustomException(ErrorCode.INVALID_INPUT, "Notion 연동 권한이 부족합니다. Notion을 다시 연동해 주세요.");
            case 429 -> new CustomException(ErrorCode.NOTION_API_UNAVAILABLE);
            // 조작·만료된 start_cursor, 권한 없는 리소스 등 — 요청 값 문제다
            default -> new CustomException(ErrorCode.INVALID_INPUT, "Notion이 요청을 거부했습니다 (HTTP " + status + ").");
        };
    }
}
