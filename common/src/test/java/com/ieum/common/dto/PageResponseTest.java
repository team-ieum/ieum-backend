package com.ieum.common.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    @Test
    void of_withHasNextTrue_includesNextCursor() {
        List<String> content = List.of("a", "b", "c");

        PageResponse<String> response = PageResponse.of(content, true, "cursor-3");

        assertThat(response.getContent()).isEqualTo(content);
        assertThat(response.getSize()).isEqualTo(3);
        assertThat(response.isHasNext()).isTrue();
        assertThat(response.getNextCursor()).isEqualTo("cursor-3");
    }

    @Test
    void of_withHasNextFalse_nextCursorIsNull() {
        List<String> content = List.of("a");

        PageResponse<String> response = PageResponse.of(content, false, "cursor-1");

        assertThat(response.isHasNext()).isFalse();
        assertThat(response.getNextCursor()).isNull();
    }

    @Test
    void of_withMapper_transformsContent() {
        List<Integer> numbers = List.of(1, 2, 3);

        PageResponse<String> response = PageResponse.of(numbers, true, "cursor-3", Object::toString);

        assertThat(response.getContent()).containsExactly("1", "2", "3");
        assertThat(response.getSize()).isEqualTo(3);
        assertThat(response.getNextCursor()).isEqualTo("cursor-3");
    }

    @Test
    void of_withMapperAndHasNextFalse_nextCursorIsNull() {
        PageResponse<String> response = PageResponse.of(List.of(1), false, "cursor-1", Object::toString);

        assertThat(response.isHasNext()).isFalse();
        assertThat(response.getNextCursor()).isNull();
    }
}
