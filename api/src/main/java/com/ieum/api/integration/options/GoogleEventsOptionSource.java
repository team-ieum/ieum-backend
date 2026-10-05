package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * 캘린더의 다가오는 일정 — 지금 이후, 시작순. 반복 일정은 회차별 항목이라 고르면 그 회차만 수정된다.
 * {@code calendar_id}는 agent 도구 {@code _calendar_segment}와 같게 정규화한다 — strip, 비면 {@code primary}, 아니면 한 번 디코드.
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
        String calendarId = calendarId(inputs.get("calendar_id"));
        // 경로 변수도 엄격 인코딩한다 — 공휴일 캘린더 id의 #·@나 조작된 /·?가 경로·쿼리를 바꾸지 않는다.
        // 형식 검사는 하지 않는다(#가 정상 값이다).
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(EVENTS_URL)
            .queryParam("timeMin", "{timeMin}")
            .queryParam("singleEvents", true)
            .queryParam("orderBy", "startTime")
            .queryParam("maxResults", 50);
        Map<String, Object> vars = new HashMap<>(Map.of(
            "calendarId", calendarId,
            "timeMin", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()));
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

    /** 캘린더 설정 URL에서 복사한 {@code %23}·{@code %40} id가 이중 인코딩되지 않게 한 번 디코드한다(캘린더 id엔 리터럴 %가 없다). */
    private static String calendarId(String raw) {
        String id = raw == null ? "" : raw.strip();
        if (id.isEmpty()) {
            return "primary";
        }
        if (id.contains("{{")) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "변수 참조로 지정된 캘린더는 실행 전이라 일정 목록을 불러올 수 없습니다.");
        }
        try {
            return UriUtils.decode(id, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 깨진 %시퀀스 — 그대로 두면 500으로 샌다
            throw new CustomException(ErrorCode.INVALID_INPUT, "calendar_id 형식이 올바르지 않습니다.");
        }
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
