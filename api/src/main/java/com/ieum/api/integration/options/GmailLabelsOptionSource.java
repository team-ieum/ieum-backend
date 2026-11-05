package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Gmail 라벨 목록(붙일 수 있는 것만). 시스템 라벨({@code type=system})은 아래 표에 있는 것만 한국어 이름으로 —
 * SENT·DRAFT는 API로 붙일 수 없고 그 밖 시스템 라벨도 뺀다. 사용자 라벨은 이름 그대로. API 순서, 페이지 없음.
 */
@Component
@RequiredArgsConstructor
public class GmailLabelsOptionSource implements OptionSource {

    private static final URI LABELS_URL = URI.create("https://gmail.googleapis.com/gmail/v1/users/me/labels");

    private static final Map<String, String> SYSTEM_NAMES = Map.ofEntries(
        Map.entry("INBOX", "받은편지함"),
        Map.entry("STARRED", "별표"),
        Map.entry("IMPORTANT", "중요"),
        Map.entry("UNREAD", "읽지 않음"),
        Map.entry("SPAM", "스팸"),
        Map.entry("TRASH", "휴지통"),
        Map.entry("CATEGORY_PERSONAL", "기본"),
        Map.entry("CATEGORY_SOCIAL", "소셜"),
        Map.entry("CATEGORY_PROMOTIONS", "프로모션"),
        Map.entry("CATEGORY_UPDATES", "업데이트"),
        Map.entry("CATEGORY_FORUMS", "포럼"));

    private final GoogleApiReader googleApiReader;

    @Override
    public String key() {
        return "google.gmail_labels";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        JsonNode body = googleApiReader.get(userId, LABELS_URL,
            GoogleApiReader.GMAIL_MODIFY_SCOPE, GoogleApiReader.GMAIL_READONLY_SCOPE);

        List<OptionItem> items = new ArrayList<>();
        body.path("labels").forEach(label -> {
            String id = label.path("id").asText();
            if ("system".equals(label.path("type").asText())) {
                String name = SYSTEM_NAMES.get(id);
                if (name != null) {
                    items.add(new OptionItem(id, name));
                }
            } else {
                items.add(new OptionItem(id, label.path("name").asText()));
            }
        });
        return new OptionPage(items, null);
    }
}
