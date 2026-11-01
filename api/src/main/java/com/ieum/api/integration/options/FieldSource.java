package com.ieum.api.integration.options;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 동적 칸 목록 공급원 (IEUM-BE-105). 카탈로그 dict 칸의 {@code fieldsSource}({@code {app}.{resource}})가
 * {@link #key()}와 같고, 빈을 하나 더하면 {@code GET /api/v1/integrations/{app}/fields/{resource}}에 바로 붙는다.
 *
 * <p>Field는 BE 전체가 Map으로 다루므로(카탈로그 로더와 같다) 레코드를 만들지 않는다. 페이지 넘김 없음.
 */
public interface FieldSource {

    /** {@code {app}.{resource}} — 예: {@code notion.properties}. */
    String key();

    /**
     * @param inputs 카탈로그 {@code fieldsInputs}에 적힌 칸 값(쿼리 파라미터 전부)
     * @return 카탈로그 Field 모양의 Map 목록 — key·title·type·required, 있을 때만 description·list·choices·
     *         optionsSource·optionsParams·children
     */
    List<Map<String, Object>> fetch(UUID userId, Map<String, String> inputs);
}
