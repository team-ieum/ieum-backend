package com.ieum.api.integration.options;

import java.util.List;

/** options API 응답. {@code nextCursor}가 null이면 마지막 페이지다. */
public record OptionPage(List<OptionItem> items, String nextCursor) {

    /** 드롭다운 한 항목. {@code id}는 노드 config에 저장되는 값, {@code name}은 표시용. */
    public record OptionItem(String id, String name) {
    }
}
