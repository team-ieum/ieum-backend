package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 캘린더의 다가오는 일정 — 지금 이후, 시작순. 반복 일정은 회차별 항목이라 고르면 그 회차만 수정된다.
 * {@code calendar_id}가 비면 {@code primary}(agent 도구 기본값과 같은 의미).
 */
@Component
@RequiredArgsConstructor
public class GoogleEventsOptionSource implements OptionSource {

    private static final String EVENTS_URL = "https://www.googleapis.com/calendar/v3/calendars/{calendarId}/events";

    private final GoogleApiReader googleApiReader;

    @Override
    public String key() {
        return "google.events";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        String calendarId = inputs.get("calendar_id");
        if (calendarId == null || calendarId.isBlank()) {
            calendarId = "primary";
        }
        // 경로 변수도 엄격 인코딩한다 — 공휴일 캘린더 id의 #·@나 조작된 /·?가 경로·쿼리를 바꾸지 않는다.
        // 형식 검사는 하지 않는다(#가 정상 값이다).
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(EVENTS_URL)
            .queryParam("timeMin", "{timeMin}")
            .queryParam("singleEvents", true)
            .queryParam("orderBy", "startTime")
            .queryParam("maxResults", 50);
        Map<String, Object> vars = new HashMap<>(Map.of(
            "calendarId", calendarId,
            "timeMin", Instant.now().toString()));
        if (cursor != null) {
            uri.queryParam("pageToken", "{cursor}");
            vars.put("cursor", cursor);
        }

        JsonNode body = googleApiReader.get(userId, uri.encode().buildAndExpand(vars).toUri(),
            GoogleApiReader.CALENDAR_SCOPE);

        List<OptionItem> items = new ArrayList<>();
        body.path("items").forEach(event ->
            items.add(new OptionItem(event.path("id").asText(), displayName(event))));
        return new OptionPage(items, body.path("nextPageToken").asText(null));
    }

    /** {@code {제목} · {yyyy-MM-dd HH:mm}} — Google이 준 시각 문자열을 그대로 자른다(타임존 변환 없음). 종일은 날짜만. */
    private static String displayName(JsonNode event) {
        String title = event.path("summary").asText("");
        if (title.isBlank()) {
            title = "(제목 없음)";
        }
        JsonNode start = event.path("start");
        String when = start.hasNonNull("dateTime")
            ? start.path("dateTime").asText().replace('T', ' ')
            : start.path("date").asText("");
        if (when.length() > 16) {
            when = when.substring(0, 16);
        }
        return when.isEmpty() ? title : title + " · " + when;
    }
}
