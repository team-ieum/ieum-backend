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
 * 연동 때 공유된 Notion 페이지 — 최근 편집순. DB 행(page)도 섞인다(Notion search가 구분하지 않음).
 * 페이지 읽기·수정·추가 도구의 {@code page_id}와 생성 도구의 {@code parent_page_id}가 함께 쓴다.
 */
@Component
@RequiredArgsConstructor
public class NotionPagesOptionSource implements OptionSource {

    private final NotionApiReader notionApiReader;

    @Override
    public String key() {
        return "notion.pages";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        JsonNode body = notionApiReader.search(userId, "page", cursor);

        List<OptionItem> items = new ArrayList<>();
        body.path("results").forEach(page ->
            items.add(new OptionItem(page.path("id").asText(), title(page))));
        return new OptionPage(items, NotionApiReader.nextCursor(body));
    }

    /** title 속성 이름은 워크스페이스마다 다르다(Name·이름·제목) — 이름이 아니라 type으로 찾는다. */
    private static String title(JsonNode page) {
        for (JsonNode property : page.path("properties")) {
            if ("title".equals(property.path("type").asText())) {
                return NotionApiReader.plainTextOrUntitled(property.path("title"));
            }
        }
        return NotionApiReader.UNTITLED;
    }
}
