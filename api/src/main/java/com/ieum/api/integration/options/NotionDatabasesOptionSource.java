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
 * 연동 때 공유된 데이터베이스의 data source 목록. id는 data source id다 — agent {@code notion_query_database}의
 * {@code database_id} 필드에 그대로 들어가고 agent가 이 id로 직접 조회한다(IEUM-AI-61).
 * 다중 source DB는 source별 항목이 되며 부모 DB 이름은 붙이지 않는다(항목마다 추가 호출이 필요해서).
 */
@Component
@RequiredArgsConstructor
public class NotionDatabasesOptionSource implements OptionSource {

    private final NotionApiReader notionApiReader;

    @Override
    public String key() {
        return "notion.databases";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        JsonNode body = notionApiReader.search(userId, "data_source", cursor);

        List<OptionItem> items = new ArrayList<>();
        // properties는 스키마 정의라 이름이 없다 — 최상위 title을 쓴다
        body.path("results").forEach(source -> items.add(new OptionItem(
            source.path("id").asText(), NotionApiReader.plainTextOrUntitled(source.path("title")))));
        return new OptionPage(items, NotionApiReader.nextCursor(body));
    }
}
