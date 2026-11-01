package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Notion DB(data source) 속성 → 동적 칸 목록 (IEUM-BE-105, spec 2026-10-26 §4). 칸 key는 Notion 속성 ID라
 * 속성 이름을 바꿔도 저장 값이 살아남는다. 쓸 수 없는 속성(formula·rollup·files·생성/수정 정보 등)은 칸을 만들지 않는다.
 * 전부 {@code required: false} — 비운 칸은 만들기에선 안 보내고 수정에선 그대로 둔다(agent).
 */
@Component
@RequiredArgsConstructor
public class NotionPropertiesFieldSource implements FieldSource {

    private static final List<Map<String, Object>> DATE_CHILDREN = List.of(
        Map.of("key", "start", "title", "시작", "type", "datetime", "required", true),
        Map.of("key", "end", "title", "끝", "type", "datetime", "required", false));

    private final NotionApiReader notionApiReader;

    @Override
    public String key() {
        return "notion.properties";
    }

    @Override
    public List<Map<String, Object>> fetch(UUID userId, Map<String, String> inputs) {
        String dataSourceId = NotionApiReader.dataSourceId(inputs);
        List<JsonNode> properties = new ArrayList<>();
        notionApiReader.dataSource(userId, dataSourceId).path("properties").forEach(properties::add);
        // title 속성 맨 앞, 나머지 이름순
        properties.sort(Comparator.comparing((JsonNode p) -> !"title".equals(p.path("type").asText()))
            .thenComparing(p -> p.path("name").asText("")));

        List<Map<String, Object>> fields = new ArrayList<>();
        for (JsonNode property : properties) {
            Map<String, Object> field = field(property);
            if (field != null) {
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * 저장 키(D5) — Notion이 준 속성 ID에서 {@code .}만 {@code %2E}로. 나머지는 이미 URL 인코딩돼 와서 그대로 둔다
     * ({@code .}은 Mongo 맵 키에서 저장 예외를 내고 정적 검증 경로를 깬다). agent가 같은 규칙으로 되돌린다.
     */
    static String fieldKey(String propertyId) {
        return propertyId.replace(".", "%2E");
    }

    /** spec §4-3 타입 매핑. 칸이 없는 타입이면 null. */
    private static Map<String, Object> field(JsonNode property) {
        String notionType = property.path("type").asText();
        Map<String, Object> extra = new LinkedHashMap<>();
        String type;
        switch (notionType) {
            case "title", "url", "email", "phone_number" -> type = "string";
            case "rich_text" -> type = "text";
            case "number" -> type = "number";
            case "checkbox" -> type = "boolean";
            case "select", "status" -> {
                type = "string";
                extra.put("choices", choices(property.path(notionType)));
            }
            case "multi_select" -> {
                type = "string";
                extra.put("list", true);
                extra.put("choices", choices(property.path(notionType)));
            }
            case "people" -> {
                type = "string";
                extra.put("list", true);
                extra.put("optionsSource", "notion.users");
            }
            case "relation" -> {
                type = "string";
                extra.put("list", true);
                extra.put("optionsSource", "notion.data_source_items");
                extra.put("optionsParams",
                    Map.of("data_source_id", property.path("relation").path("data_source_id").asText()));
            }
            case "date" -> {
                type = "object";
                extra.put("children", DATE_CHILDREN);
            }
            default -> {
                return null;
            }
        }
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("key", fieldKey(property.path("id").asText()));
        field.put("title", property.path("name").asText());
        field.put("type", type);
        field.put("required", false);
        String description = property.path("description").asText("");
        if (!description.isBlank()) {
            field.put("description", description);
        }
        field.putAll(extra);
        return field;
    }

    /** 옵션 → {@code {id: 옵션 ID, name: 옵션 이름}} — 이름을 바꿔도 저장 값(ID)이 유지된다. */
    private static List<Map<String, String>> choices(JsonNode config) {
        List<Map<String, String>> choices = new ArrayList<>();
        config.path("options").forEach(option ->
            choices.add(Map.of("id", option.path("id").asText(), "name", option.path("name").asText())));
        return choices;
    }
}
