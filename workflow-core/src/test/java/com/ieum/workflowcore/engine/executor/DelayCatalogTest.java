package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.catalog.EventDefinition;
import com.ieum.workflowcore.domain.enums.NodeType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 카탈로그 {@code delay.*} ↔ {@link DelayNodeExecutor#EVENTS} 일치. */
class DelayCatalogTest {

    private static final List<EventDefinition> DELAYS = EventCatalog.loadFromClasspath().events().stream()
        .filter(e -> e.name().startsWith("delay.")).toList();

    @Test
    @DisplayName("카탈로그_delay는_실행기_EVENTS와_같다")
    void catalogDelaysMatchExecutorEvents() {
        assertThat(DELAYS).extracting(EventDefinition::name)
            .containsExactlyInAnyOrderElementsOf(DelayNodeExecutor.EVENTS);
    }

    @Test
    @DisplayName("delay는_DELAY다")
    void delaysAreDelayNodeType() {
        assertThat(DELAYS).isNotEmpty()
            .allSatisfy(e -> assertThat(e.nodeType()).as(e.name()).isEqualTo(NodeType.DELAY));
    }
}
