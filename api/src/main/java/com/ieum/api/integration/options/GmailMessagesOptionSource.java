package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Gmail 메일 — 검색어 {@code q}(공백뿐이면 없음)가 없으면 받은편지함 최근 20개, 있으면 전체 메일(스팸·휴지통 제외)에서 20개씩.
 * 목록은 ID만 주므로 항목마다 metadata를 순차 조회한다 — 한 페이지 Google 호출 21번(목록 1 + 항목 20)은 설계가 받아들인 비용.
 * 항목 조회의 403·404(그사이 지워진 메일 — GoogleApiReader가 둘을 합친다)는 건너뛰고, 그 밖 오류는 그대로 던진다.
 */
@Component
@RequiredArgsConstructor
public class GmailMessagesOptionSource implements OptionSource {

    private static final String MESSAGES_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages";
    private static final DateTimeFormatter WHEN =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final GoogleApiReader googleApiReader;

    @Override
    public String key() {
        return "google.gmail_messages";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        return fetchPage(googleApiReader, userId, MESSAGES_URL, "messages", inputs, cursor, message -> message);
    }

    /** 메일·대화 공용 — 목록 → 항목 metadata 순차 조회. {@code firstMessage}는 항목 응답에서 이름을 낼 메일을 고른다 */
    static OptionPage fetchPage(GoogleApiReader reader, UUID userId, String baseUrl, String listField,
                                Map<String, String> inputs, String cursor, Function<JsonNode, JsonNode> firstMessage) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl);
        Map<String, Object> vars = new HashMap<>();
        String q = inputs.get("q");
        if (q != null && !q.isBlank()) {
            uri.queryParam("q", "{q}");
            vars.put("q", q.strip());
        } else {
            uri.queryParam("labelIds", "INBOX");
        }
        uri.queryParam("maxResults", 20);
        if (cursor != null) {
            uri.queryParam("pageToken", "{cursor}");
            vars.put("cursor", cursor);
        }
        JsonNode body = reader.get(userId, uri.encode().buildAndExpand(vars).toUri(),
            GoogleApiReader.GMAIL_MODIFY_SCOPE, GoogleApiReader.GMAIL_READONLY_SCOPE);

        List<OptionItem> items = new ArrayList<>();
        for (JsonNode entry : body.path(listField)) {
            String id = entry.path("id").asText();
            JsonNode item;
            try {
                item = reader.get(userId, UriComponentsBuilder.fromUriString(baseUrl + "/{id}")
                        .queryParam("format", "metadata")
                        .queryParam("metadataHeaders", "Subject", "From", "Date")
                        .encode().buildAndExpand(id).toUri(),
                    GoogleApiReader.GMAIL_MODIFY_SCOPE, GoogleApiReader.GMAIL_READONLY_SCOPE);
            } catch (CustomException e) {
                if (e.getErrorCode() == ErrorCode.GOOGLE_RESOURCE_NOT_FOUND) {
                    continue;
                }
                throw e;
            }
            items.add(new OptionItem(id, gmailDisplayName(firstMessage.apply(item))));
        }
        return new OptionPage(items, body.path("nextPageToken").asText(null));
    }

    /** {@code {제목} — {From} · {yyyy-MM-dd HH:mm}}(KST, internalDate). 제목이 비면 (제목 없음), From이 비면 ' — From' 생략 */
    static String gmailDisplayName(JsonNode message) {
        String subject = header(message, "Subject");
        String from = header(message, "From");
        StringBuilder name = new StringBuilder(subject.isBlank() ? "(제목 없음)" : subject);
        if (!from.isBlank()) {
            name.append(" — ").append(from);
        }
        long internalDate = message.path("internalDate").asLong(-1); // Gmail은 문자열로 준다
        if (internalDate > 0) {
            name.append(" · ").append(WHEN.format(Instant.ofEpochMilli(internalDate)));
        }
        return name.toString();
    }

    private static String header(JsonNode message, String name) {
        for (JsonNode header : message.path("payload").path("headers")) {
            if (name.equalsIgnoreCase(header.path("name").asText())) {
                return header.path("value").asText("").strip();
            }
        }
        return "";
    }
}
