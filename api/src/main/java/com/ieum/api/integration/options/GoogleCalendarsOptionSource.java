package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 사용자 캘린더 목록 — 읽기 전용(공휴일·구독)도 포함한다. 같은 {@code calendar_id} 필드를 일정 생성·조회·수정
 * 도구가 함께 쓰는데, 조회로 공휴일을 읽는 건 정당한 용도라서다. free/busy만 공유된 캘린더는 일정을 읽을 수 없어
 * {@code minAccessRole=reader}로 뺀다.
 */
@Component
@RequiredArgsConstructor
public class GoogleCalendarsOptionSource implements OptionSource {

    private static final String CALENDAR_LIST_URL = "https://www.googleapis.com/calendar/v3/users/me/calendarList";

    private final GoogleApiReader googleApiReader;

    @Override
    public String key() {
        return "google.calendars";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(CALENDAR_LIST_URL)
            .queryParam("maxResults", 250)
            .queryParam("minAccessRole", "reader");
        Map<String, Object> vars = new HashMap<>();
        if (cursor != null) {
            uri.queryParam("pageToken", "{cursor}");
            vars.put("cursor", cursor);
        }

        JsonNode body = googleApiReader.get(userId, uri.encode().buildAndExpand(vars).toUri(),
            GoogleApiReader.CALENDAR_SCOPE);

        List<OptionItem> items = new ArrayList<>();
        body.path("items").forEach(calendar -> {
            String override = calendar.path("summaryOverride").asText("");
            String name = override.isBlank() ? calendar.path("summary").asText() : override;
            items.add(new OptionItem(calendar.path("id").asText(), name));
        });
        return new OptionPage(items, body.path("nextPageToken").asText(null));
    }
}
