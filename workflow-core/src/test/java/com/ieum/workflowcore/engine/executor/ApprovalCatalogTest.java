package com.ieum.workflowcore.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.catalog.EventDefinition;
import com.ieum.workflowcore.domain.enums.NodeType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 카탈로그 {@code node_type APPROVAL} ↔ {@link ApprovalGateConfig#EVENTS} 일치. */
class ApprovalCatalogTest {

    private static final List<EventDefinition> APPROVALS = EventCatalog.loadFromClasspath().events().stream()
        .filter(e -> e.nodeType() == NodeType.APPROVAL).toList();

    @Test
    @DisplayName("카탈로그_APPROVAL은_게이트_EVENTS와_같다")
    void catalogApprovalsMatchGateEvents() {
        assertThat(APPROVALS).extracting(EventDefinition::name)
            .containsExactlyInAnyOrderElementsOf(ApprovalGateConfig.EVENTS);
    }
}
