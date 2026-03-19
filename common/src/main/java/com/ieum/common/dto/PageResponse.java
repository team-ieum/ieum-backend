package com.ieum.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Getter
@Builder(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageResponse<T> {

    private final List<T> content;
    private final int size;
    private final boolean hasNext;
    private final String nextCursor;

    public static <T> PageResponse<T> of(List<T> content, boolean hasNext, String nextCursor) {
        return PageResponse.<T>builder()
                .content(content)
                .size(content.size())
                .hasNext(hasNext)
                .nextCursor(hasNext ? nextCursor : null)
                .build();
    }

    public static <T, R> PageResponse<R> of(List<T> content, boolean hasNext, String nextCursor, Function<T, R> mapper) {
        List<R> mapped = content.stream().map(mapper).collect(Collectors.toList());
        return PageResponse.<R>builder()
                .content(mapped)
                .size(mapped.size())
                .hasNext(hasNext)
                .nextCursor(hasNext ? nextCursor : null)
                .build();
    }
}
