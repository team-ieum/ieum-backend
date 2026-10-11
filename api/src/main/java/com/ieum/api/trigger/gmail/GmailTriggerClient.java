package com.ieum.api.trigger.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.GoogleApiReader;
import com.ieum.api.trigger.AppTriggerSampleFetcher;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.service.AppTriggerSubscriptionService;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    /** Gmail messages.list maxResults 상한 */
    static final int PAGE_MAX = 500;

    private final GoogleApiReader reader;

    /**
     * 노드 config(또는 구독 스냅샷)의 검색식. 비면 받은편지함에서 내가 보낸 메일을 뺀 전체.
     * 자기에게 보낸 메일은 INBOX+SENT라 "새 메일 → 나에게 메일" 워크플로우가 자기 발화로 끝없이 이어진다.
     * blank 판정은 구독 스냅샷과 같은 규칙을 쓴다 — 어긋나면 커서 리셋 판단과 실제 검색식이 달라진다.
     */
    public static String baseQuery(Map<?, ?> config) {
        return Objects.requireNonNullElse(AppTriggerSubscriptionService.gmailQuery(config), DEFAULT_QUERY);
    }

    /** @return 메시지 id — Gmail 목록 순서(최신순), 첫 페이지만 */
    public List<String> listMessageIds(UUID userId, String q, int maxResults) {
        List<String> ids = new ArrayList<>();
        listPage(userId, q, maxResults, null).path("messages").forEach(m -> ids.add(m.path("id").asText()));
        return ids;
    }

    /**
     * 모든 페이지의 메시지 id(최신순). 스윕러용 — 미발화 적체가 한 페이지를 넘으면 첫 페이지에 없는 오래된 메일을
     * 커서가 지나쳐 영구 유실된다. 적체가 없으면 호출 1회다.
     */
    public List<String> listAllMessageIds(UUID userId, String q) {
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        do {
            JsonNode page = listPage(userId, q, PAGE_MAX, pageToken);
            page.path("messages").forEach(m -> ids.add(m.path("id").asText()));
            pageToken = page.hasNonNull("nextPageToken") ? page.get("nextPageToken").asText() : null;
        } while (pageToken != null);
        return ids;
    }

    private JsonNode listPage(UUID userId, String q, int maxResults, String pageToken) {
        Map<String, String> vars = new HashMap<>();
        vars.put("q", q);
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(MESSAGES_URL)
            .queryParam("q", "{q}")
            .queryParam("maxResults", maxResults);
        if (pageToken != null) {
            builder.queryParam("pageToken", "{pageToken}");
            vars.put("pageToken", pageToken);
        }
        URI uri = builder.encode().buildAndExpand(vars).toUri();
        return reader.get(userId, uri, GMAIL_MODIFY_SCOPE, GMAIL_READONLY_SCOPE);
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
