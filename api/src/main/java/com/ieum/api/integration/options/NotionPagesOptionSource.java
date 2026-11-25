package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 연동 때 공유된 Notion 페이지 — 최근 편집순, DB 행은 뺀다(설계 결정 ①, IEUM-BE-125). DB 행은
 * {@code notion.databases} → {@code notion.data_source_items} 연쇄로 고른다. Notion search는 페이지와 행을 구분하지
 * 않아 결과의 {@code parent.type}으로 거른다. 걸러서 {@link #MIN_ITEMS}개가 안 차면 다음 커서를 이어 읽는다(최대
 * {@link #MAX_CALLS}번 — 행만 많은 워크스페이스에서 끝없이 읽지 않게).
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

    static final int MIN_ITEMS = 20;
    static final int MAX_CALLS = 3;
    /** 부모가 DB면 행이다 — 2025-09 이후 data_source_id, 그 전 객체는 database_id. */
    private static final Set<String> ROW_PARENTS = Set.of("data_source_id", "database_id");

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        List<OptionItem> items = new ArrayList<>();
        String next = cursor;
        for (int call = 0; call < MAX_CALLS; call++) {
            JsonNode body = notionApiReader.search(userId, "page", next);
            body.path("results").forEach(page -> {
                if (!ROW_PARENTS.contains(page.path("parent").path("type").asText())) {
                    items.add(new OptionItem(page.path("id").asText(), title(page)));
                }
            });
            next = NotionApiReader.nextCursor(body);
            if (next == null || items.size() >= MIN_ITEMS) {
                break;
            }
        }
        return new OptionPage(items, next);
    }

    /** title 속성 이름은 워크스페이스마다 다르다(Name·이름·제목) — 이름이 아니라 type으로 찾는다. DB 행 드롭다운도 쓴다. */
    static String title(JsonNode page) {
        for (JsonNode property : page.path("properties")) {
            if ("title".equals(property.path("type").asText())) {
                return NotionApiReader.plainTextOrUntitled(property.path("title"));
            }
        }
        return NotionApiReader.UNTITLED;
    }
}
