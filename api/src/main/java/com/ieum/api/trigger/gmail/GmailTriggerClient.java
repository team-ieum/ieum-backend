package com.ieum.api.trigger.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.GoogleApiReader;
import com.ieum.api.trigger.AppTriggerSampleFetcher;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.enums.TriggerType;
import com.ieum.workflowcore.trigger.PollStart;
import com.ieum.workflowcore.trigger.TriggerHandler;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * GMAIL_NEW_EMAIL 트리거 핸들러 + Gmail 외부 호출(목록·단건 조회). 토큰·scope·오류 매핑은 {@link GoogleApiReader}가 한다.
 * 폴링 루프는 {@link GmailTriggerSweeper}가 따로 돈다. 트랜잭션을 걸지 않는다(토큰 갱신 저장이 readOnly에 합류해 유실된다).
 */
@Component
@RequiredArgsConstructor
public class GmailTriggerClient implements AppTriggerSampleFetcher, TriggerHandler {

    /** 기존 scope 그룹 {@code gmail}(application.yml). readonly도 읽기엔 충분하다 */
    static final String GMAIL_MODIFY_SCOPE = "https://www.googleapis.com/auth/gmail.modify";
    static final String GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    private static final String MESSAGES_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages";

    static final String DEFAULT_QUERY = "in:inbox -in:sent";
    /** Gmail messages.list maxResults 상한 */
    static final int PAGE_MAX = 500;
    static final int MAX_PAGES = 10;

    private final GoogleApiReader reader;

    /** Gmail 검색식 정규화 — trim, blank는 null. 구독 스냅샷({@link #snapshot})과 실제 검색식({@link #baseQuery})이 함께 쓴다 */
    public static String query(Map<?, ?> config) {
        return config != null && config.get("query") instanceof String q && !q.isBlank() ? q.trim() : null;
    }

    /**
     * 노드 config(또는 구독 스냅샷)의 검색식. 비면 받은편지함에서 내가 보낸 메일을 뺀 전체, 있으면 그 식에서 내가 보낸 메일을 뺀다.
     * 보낸 메일이 검색식에 맞으면 "새 메일 → 메일 보내기" 워크플로우가 자기 발화로 끝없이 이어진다(PR #89 리뷰 결정 — 보낸 메일
     * 트리거는 지원하지 않는다). blank 판정은 구독 스냅샷과 같은 규칙을 쓴다 — 어긋나면 커서 리셋 판단과 실제 검색식이 달라진다.
     */
    public static String baseQuery(Map<?, ?> config) {
        String query = query(config);
        return query == null ? DEFAULT_QUERY : "(" + query + ") -in:sent";
    }

    /** @return 메시지 id — Gmail 목록 순서(최신순), 첫 페이지만 */
    public List<String> listMessageIds(UUID userId, String q, int maxResults) {
        List<String> ids = new ArrayList<>();
        listPage(userId, q, maxResults, null).path("messages").forEach(m -> ids.add(m.path("id").asText()));
        return ids;
    }

    /**
     * 모든 페이지의 메시지 id(최신순), 최대 {@link #MAX_PAGES}페이지. 스윕러용 — 미발화 적체가 한 페이지를 넘으면 첫 페이지에
     * 없는 오래된 메일을 커서가 지나쳐 영구 유실된다. 적체가 없으면 호출 1회다. 상한을 넘으면 검색식이 기간 제한을 벗어났거나
     * 적체가 비정상인 것 — 메일함 전체를 받지 않게 INVALID_INPUT으로 끊는다(오류 기록·커서 유지, 24시간 상한이 결국 회복).
     */
    public List<String> listAllMessageIds(UUID userId, String q) {
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        int pages = 0;
        do {
            if (++pages > MAX_PAGES) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                    "검색 결과가 " + PAGE_MAX * MAX_PAGES + "건을 넘습니다 — 검색식을 좁혀 주세요.");
            }
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
    public TriggerType type() {
        return TriggerType.GMAIL_NEW_EMAIL;
    }

    @Override
    public boolean subscribes() {
        return true;
    }

    /**
     * 구독 스냅샷 {@code {query?}} — 나머지(brand 등)는 저장하지 않는다. blank query는 키 생략과 같게 빠진다 — 다르게 보면 저장마다
     * 커서가 리셋돼 메일이 유실된다({@code AppTriggerSubscription.reconfigure}가 스냅샷 전체를 비교한다).
     */
    @Override
    public Map<String, Object> snapshot(Map<?, ?> config) {
        Map<String, Object> snapshot = new HashMap<>();
        String query = query(config);
        if (query != null) {
            snapshot.put("query", query);
        }
        return snapshot;
    }

    /** "지금 이후 메일만" — 나머지 커서 키(floorSec·seen)는 {@link GmailCursor}가 채운다(floorSec가 없으면 afterSec가 하한), 즉시 폴링 */
    @Override
    public PollStart pollStart(LocalDateTime now) {
        return new PollStart(Map.of("afterSec", now.atZone(ZoneId.systemDefault()).toEpochSecond()), now);
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
