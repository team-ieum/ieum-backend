package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 사용자 캘린더 목록 — 빈은 {@link GoogleCalendarOptionSourceConfig}가 두 키로 만든다.
 *
 * <ul>
 *   <li>{@code google.calendars}: 읽기 전용(공휴일·구독)도 포함한다 — 일정 찾기가 공휴일을 읽는 건 정당한 용도다.
 *       읽기 전용은 이름 뒤에 표시한다.</li>
 *   <li>{@code google.writable_calendars}: 일정을 쓸 수 있는 캘린더만, 꼬리표 없음. {@code minAccessRole} 허용값에
 *       writerWithoutPrivateAccess가 없어 writer로 거르면 포함 여부가 문서에 없다 — reader까지 받아 {@code accessRole}로
 *       직접 거른다. 모르는 값은 뺀다. 한 페이지가 전부 걸러져도 다음 커서는 넘긴다.</li>
 * </ul>
 * 둘 다 free/busy만 공유된 캘린더는 {@code minAccessRole=reader}로 뺀다.
 */
@RequiredArgsConstructor
public class GoogleCalendarsOptionSource implements OptionSource {

    private static final String CALENDAR_LIST_URL = "https://www.googleapis.com/calendar/v3/users/me/calendarList";
    private static final Set<String> WRITABLE_ROLES = Set.of("writerWithoutPrivateAccess", "writer", "owner");

    private final String key;
    private final boolean writableOnly;
    private final GoogleApiReader googleApiReader;

    @Override
    public String key() {
        return key;
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
        for (JsonNode calendar : body.path("items")) {
            String role = calendar.path("accessRole").asText();
            if (writableOnly && !WRITABLE_ROLES.contains(role)) {
                continue;
            }
            String override = calendar.path("summaryOverride").asText("");
            String name = override.isBlank() ? calendar.path("summary").asText() : override;
            if ("reader".equals(role)) {
                name += " (읽기 전용)";
            }
            items.add(new OptionItem(calendar.path("id").asText(), name));
        }
        return new OptionPage(items, body.path("nextPageToken").asText(null));
    }
}
