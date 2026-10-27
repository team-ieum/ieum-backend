package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Notion 워크스페이스 사용자 — 사람만(봇 제외, Notion은 게스트를 주지 않는다), 값은 사용자 ID (IEUM-BE-105).
 * people 칸 드롭다운. 검색 API가 없어 q는 읽지 않는다. 연결에 user information capability가 없으면 Notion 403 →
 * INVALID_INPUT(권한 부족).
 */
@Component
@RequiredArgsConstructor
public class NotionUsersOptionSource implements OptionSource {

    private final NotionApiReader notionApiReader;

    @Override
    public String key() {
        return "notion.users";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        JsonNode body = notionApiReader.users(userId, cursor);

        List<OptionItem> items = new ArrayList<>();
        body.path("results").forEach(user -> {
            if ("person".equals(user.path("type").asText())) {
                String id = user.path("id").asText();
                items.add(new OptionItem(id, user.path("name").asText(id)));
            }
        });
        return new OptionPage(items, NotionApiReader.nextCursor(body));
    }
}
