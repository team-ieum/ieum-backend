package com.ieum.api.integration.options;

import java.util.Map;
import java.util.UUID;

/**
 * 노드 설정 드롭다운의 선택지 공급원 (IEUM-BE-71).
 *
 * <p>agent 도구 필드 스키마의 {@code optionsSource}({@code {app}.{resource}})가 {@link #key()}와 같다.
 * 새 공급원(Calendar·Notion 등)은 이 인터페이스의 빈을 하나 더하면 options API에 바로 붙는다.
 */
public interface OptionSource {

    /** {@code {app}.{resource}} — 예: {@code google.spreadsheets}. */
    String key();

    /**
     * @param inputs 스키마 {@code optionsInputs}에 적힌 다른 필드 값(쿼리 파라미터에서 cursor를 뺀 전부)
     * @param cursor 이전 페이지의 {@code nextCursor}. 첫 페이지면 null
     */
    OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor);
}
