package com.ieum.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ieum.api.chat.service.AgentClient;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.catalog.EventDefinition;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class AgentCatalogDiffReporterTest {

    private static final EventCatalog CATALOG = EventCatalog.loadFromClasspath();
    private static final List<String> BUILDER = CATALOG.events().stream()
            .filter(EventDefinition::builder).map(EventDefinition::name).toList();

    private final AgentClient agentClient = mock(AgentClient.class);
    private final AgentCatalogDiffReporter reporter = new AgentCatalogDiffReporter(agentClient, CATALOG);

    @Test
    @DisplayName("diff — 양쪽에만 있는 id를 각각 모은다")
    void diff_splitsBothSides() {
        AgentCatalogDiffReporter.Diff diff = AgentCatalogDiffReporter.diff(Set.of("a", "b"), Set.of("b", "c"));

        assertThat(diff.onlyBe()).containsExactly("a");
        assertThat(diff.onlyAgent()).containsExactly("c");
    }

    @Test
    @DisplayName("diff — 같으면 둘 다 비어 있다")
    void diff_same() {
        AgentCatalogDiffReporter.Diff diff = AgentCatalogDiffReporter.diff(Set.of("a"), Set.of("a"));

        assertThat(diff.onlyBe()).isEmpty();
        assertThat(diff.onlyAgent()).isEmpty();
    }

    @Test
    @DisplayName("agent가 BE builder 이벤트와 같은 id를 주면 경고 없음 — builder=false(gmail.send_email)는 비교에서 빠진다")
    void report_sameIds_noWarn(CapturedOutput output) {
        given(agentClient.getNodeCatalog()).willReturn(entries(BUILDER));

        reporter.compare();

        assertThat(output).doesNotContain("[CatalogDiff]");
    }

    @Test
    @DisplayName("차이가 있으면 쪽마다 한 줄씩 warn")
    void report_diff_warnsEachSide(CapturedOutput output) {
        String dropped = BUILDER.get(0);
        List<String> agentIds = new java.util.ArrayList<>(BUILDER.subList(1, BUILDER.size()));
        agentIds.add("zzz.extra");
        given(agentClient.getNodeCatalog()).willReturn(entries(agentIds));

        reporter.compare();

        assertThat(output).contains("[CatalogDiff] BE에만 있는 노드: " + dropped)
                .contains("[CatalogDiff] agent에만 있는 노드: zzz.extra");
    }

    @Test
    @DisplayName("agent 호출 실패는 삼키고 warn만 — 기동을 막지 않는다")
    void report_agentFails_swallowed(CapturedOutput output) {
        given(agentClient.getNodeCatalog()).willThrow(new CustomException(ErrorCode.PROVIDER_ERROR));

        assertThatCode(reporter::compare).doesNotThrowAnyException();
        assertThat(output).contains("[CatalogDiff] agent 카탈로그 대조 실패");
    }

    private static ObjectNode entries(List<String> ids) {
        ObjectNode body = new ObjectMapper().createObjectNode();
        ArrayNode arr = body.putArray("entries");
        ids.forEach(id -> arr.addObject().put("id", id));
        return body;
    }
}
