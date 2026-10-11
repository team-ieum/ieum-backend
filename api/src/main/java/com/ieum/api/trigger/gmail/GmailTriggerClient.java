package com.ieum.api.trigger.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.GoogleApiReader;
import com.ieum.api.trigger.AppTriggerSampleFetcher;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Gmail 트리거의 외부 호출 — 목록·단건 조회. 토큰·scope·오류 매핑은 {@link GoogleApiReader}가 한다.
 * 트랜잭션을 걸지 않는다(토큰 갱신 저장이 readOnly에 합류해 유실된다).
 */
@Component
@RequiredArgsConstructor
public class GmailTriggerClient implements AppTriggerSampleFetcher {

    /** 기존 scope 그룹 {@code gmail}(application.yml). readonly도 읽기엔 충분하다 */
    static final String GMAIL_MODIFY_SCOPE = "https://www.googleapis.com/auth/gmail.modify";
    static final String GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    private static final String MESSAGES_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages";

    static final String DEFAULT_QUERY = "in:inbox -in:sent";

    private final GoogleApiReader reader;

    /**
     * 노드 config(또는 구독 스냅샷)의 검색식. 비면 받은편지함에서 내가 보낸 메일을 뺀 전체.
     * 자기에게 보낸 메일은 INBOX+SENT라 "새 메일 → 나에게 메일" 워크플로우가 자기 발화로 끝없이 이어진다.
     */
    public static String baseQuery(Map<?, ?> config) {
        return config != null && config.get("query") instanceof String q && !q.isBlank() ? q.trim() : DEFAULT_QUERY;
    }

    /** @return 메시지 id — Gmail 목록 순서(최신순) */
    public List<String> listMessageIds(UUID userId, String q, int maxResults) {
        URI uri = UriComponentsBuilder.fromUriString(MESSAGES_URL)
            .queryParam("q", "{q}")
            .queryParam("maxResults", maxResults)
            .encode().buildAndExpand(Map.of("q", q)).toUri();
        List<String> ids = new ArrayList<>();
        reader.get(userId, uri, GMAIL_MODIFY_SCOPE, GMAIL_READONLY_SCOPE)
            .path("messages").forEach(m -> ids.add(m.path("id").asText()));
        return ids;
    }

    public JsonNode getMessage(UUID userId, String messageId) {
        URI uri = UriComponentsBuilder.fromUriString(MESSAGES_URL + "/{id}")
            .queryParam("format", "full")
            .encode().buildAndExpand(Map.of("id", messageId)).toUri();
        return reader.get(userId, uri, GMAIL_MODIFY_SCOPE, GMAIL_READONLY_SCOPE);
    }

    @Override
    public TriggerType triggerType() {
        return TriggerType.GMAIL_NEW_EMAIL;
    }

    @Override
    public Optional<Map<String, Object>> fetchLatest(UUID userId, Map<String, Object> config) {
        List<String> ids = listMessageIds(userId, baseQuery(config), 1);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(GmailMessageNormalizer.normalize(getMessage(userId, ids.get(0))));
    }
}
