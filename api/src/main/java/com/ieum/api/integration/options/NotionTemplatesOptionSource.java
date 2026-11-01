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
 * Notion DB(data source) 템플릿 (IEUM-BE-105). 검색어 없는 첫 페이지 맨 앞에 특수 값 {@code default}("기본 템플릿")를
 * 둔다 — agent가 {@code {type: "default"}}로 보낸다. 기본 템플릿이 없는 DB에도 보이며 고르면 실행이 Notion 400으로
 * 안내한다(spec §5 — is_default로 거르지 않는다: 다음 페이지에 있을 수 있고 저장 뒤 바뀔 수 있다).
 */
@Component
@RequiredArgsConstructor
public class NotionTemplatesOptionSource implements OptionSource {

    static final OptionItem DEFAULT_TEMPLATE = new OptionItem("default", "기본 템플릿");

    private final NotionApiReader notionApiReader;

    @Override
    public String key() {
        return "notion.templates";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        String dataSourceId = NotionApiReader.dataSourceId(inputs);
        String q = NotionApiReader.query(inputs);
        JsonNode body = notionApiReader.templates(userId, dataSourceId, q, cursor);

        List<OptionItem> items = new ArrayList<>();
        if (q == null && cursor == null) {
            items.add(DEFAULT_TEMPLATE);
        }
        body.path("templates").forEach(template -> {
            String name = template.path("name").asText("");
            items.add(new OptionItem(template.path("id").asText(), name.isBlank() ? NotionApiReader.UNTITLED : name));
        });
        return new OptionPage(items, NotionApiReader.nextCursor(body));
    }
}
