package com.ieum.api.integration.options;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Sheets 쓰기 노드의 {@code columns} 칸. key는 열 문자라 헤더 이름이 바뀌어도 저장값이 같은 열을 가리킨다.
 * 전부 {@code required: false} — 비운 칸은 agent가 안 채움으로 본다.
 */
@Component
@RequiredArgsConstructor
public class GoogleSheetColumnsFieldSource implements FieldSource {

    private final GoogleSheetColumnsReader columnsReader;

    @Override
    public String key() {
        return "google.sheet_columns";
    }

    @Override
    public List<Map<String, Object>> fetch(UUID userId, Map<String, String> inputs) {
        return columnsReader.columns(userId, inputs).stream()
            .map(c -> Map.<String, Object>of("key", c.letter(),
                "title", c.header() == null ? c.letter() : c.header() + " (" + c.letter() + ")",
                "type", "string", "required", false))
            .toList();
    }
}
