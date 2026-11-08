package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.catalog.EventDefinition;
import com.ieum.workflowcore.domain.enums.NodeType;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 카탈로그 {@code formatter.*} ↔ {@link Formatter} 표 양방향 일치. Formatter가 package-private이라 이 패키지에 둔다. */
class FormatterCatalogTest {

    private static final EventCatalog CATALOG = EventCatalog.loadFromClasspath();
    private static final List<EventDefinition> FORMATTERS = CATALOG.events().stream()
        .filter(e -> e.name().startsWith("formatter.")).toList();

    private static String catalogTitle(String event) throws IOException {
        String file = "catalog/events/formatter/" + event.substring("formatter.".length()) + ".json";
        try (InputStream in = FormatterCatalogTest.class.getClassLoader().getResourceAsStream(file)) {
            assertThat(in).as(file).isNotNull();
            return (String) new ObjectMapper().readValue(in, Map.class).get("title");
        }
    }

    @Test
    @DisplayName("카탈로그_포맷터는_모두_실행기가_있다 — 22개, title도 같다")
    void catalogFormattersHaveExecutors() throws IOException {
        assertThat(FORMATTERS).hasSize(22);
        for (EventDefinition e : FORMATTERS) {
            assertThat(Formatter.supports(e.name())).as(e.name()).isTrue();
            assertThat(Formatter.title(e.name())).as(e.name()).isEqualTo(catalogTitle(e.name()));
        }
    }

    @Test
    @DisplayName("실행기_포맷터는_모두_카탈로그에_있다")
    void executorFormattersAreInCatalog() {
        assertThat(FORMATTERS).extracting(EventDefinition::name)
            .containsExactlyInAnyOrderElementsOf(Formatter.events());
    }

    @Test
    @DisplayName("포맷터는_TRANSFORM이다")
    void formattersAreTransform() {
        assertThat(FORMATTERS).allSatisfy(e -> assertThat(e.nodeType()).as(e.name()).isEqualTo(NodeType.TRANSFORM));
    }
}
