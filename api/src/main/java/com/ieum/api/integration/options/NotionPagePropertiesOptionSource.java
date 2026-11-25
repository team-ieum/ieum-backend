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
 * 페이지(DB 행 포함)의 속성 — 값은 속성 id, 이름은 "속성 이름 (type)" (IEUM-BE-125). '속성 하나 가져오기'
 * 노드(agent {@code notion_get_property})의 {@code property_id}가 쓴다 — 그 API는 이름이 아니라 id로 부른다.
 * {@code page_id} 필수. 한 페이지의 속성은 한 번에 오므로 커서가 없고, 검색어도 받지 않는다.
 * 기존 {@code notion.properties}는 동적 칸(FieldSource) 전용이라 선택 드롭다운으로 쓸 수 없다.
 */
@Component
@RequiredArgsConstructor
public class NotionPagePropertiesOptionSource implements OptionSource {

    private final NotionApiReader notionApiReader;

    @Override
    public String key() {
        return "notion.page_properties";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        String pageId = NotionApiReader.pageId(inputs);
        JsonNode properties = notionApiReader.page(userId, pageId).path("properties");

        List<OptionItem> items = new ArrayList<>();
        properties.properties().forEach(entry -> {
            JsonNode property = entry.getValue();
            String type = property.path("type").asText();
            items.add(new OptionItem(property.path("id").asText(),
                type.isEmpty() ? entry.getKey() : entry.getKey() + " (" + type + ")"));
        });
        return new OptionPage(items, null);
    }
}
