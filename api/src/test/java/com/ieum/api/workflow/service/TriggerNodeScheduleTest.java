package com.ieum.api.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.catalog.EventCatalog;
import com.ieum.workflowcore.catalog.EventDefinition;
import com.ieum.workflowcore.catalog.NodeDerivation;
import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 파생된 노드 → 워크플로우 트리거 타입·cron 계산 (IEUM-BE-92). REST 생성·수정과 AI 저장이 같은 규칙을 쓴다. */
class TriggerNodeScheduleTest {

    private static final EventCatalog CATALOG = EventCatalog.loadFromClasspath();
    private final NodeDerivation derivation = new NodeDerivation(CATALOG);

    /** 저장 경로와 같은 모양이다. 파생을 거친 노드를 만든다. */
    private Map<String, Object> node(String event, Map<String, Object> config) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", "n-" + event);
        node.put("event", event);
        node.put("config", config);
        return derivation.derive(node);
    }

    private static TriggerNodeSchedule compute(List<Map<String, Object>> nodes) {
        return TriggerNodeSchedule.compute(nodes, CATALOG);
    }

    @Test
    @DisplayName("SCHEDULE 트리거 event면 SCHEDULE과 config.cron 원문을 함께 읽는다")
    void schedule_readsEventTriggerTypeAndCron() {
        assertThat(compute(List.of(node("http.request", Map.of()),
            node("schedule.every", Map.of("cron", "0 9 * * *")))))
            .isEqualTo(new TriggerNodeSchedule(TriggerType.SCHEDULE, "0 9 * * *"));
    }

    @Test
    @DisplayName("cron은 SCHEDULE일 때 문자열만 읽는다 — 다른 트리거의 cron·비문자열·누락은 null")
    void cron_onlyForScheduleString() {
        assertThat(compute(List.of(node("webhook.catch", Map.of("cron", "0 9 * * *")))))
            .isEqualTo(new TriggerNodeSchedule(TriggerType.WEBHOOK, null));
        assertThat(compute(List.of(node("schedule.every", Map.of("cron", 900)))))
            .isEqualTo(new TriggerNodeSchedule(TriggerType.SCHEDULE, null));
        assertThat(compute(List.of(node("schedule.every", Map.of()))))
            .isEqualTo(new TriggerNodeSchedule(TriggerType.SCHEDULE, null));
    }

    @Test
    @DisplayName("TRIGGER 노드가 없으면 (MANUAL, null)")
    void noTriggerNode_isManual() {
        assertThat(compute(List.of(node("http.request", Map.of("cron", "0 9 * * *")))))
            .isEqualTo(new TriggerNodeSchedule(TriggerType.MANUAL, null));
        assertThat(compute(List.of())).isEqualTo(new TriggerNodeSchedule(TriggerType.MANUAL, null));
    }

    @Test
    @DisplayName("TRIGGER 노드가 여럿이면 첫 번째를 쓴다 — 실행(SyncExecutionRuntime)과 같은 선택")
    void multipleTriggers_firstWins() {
        assertThat(compute(List.of(node("webhook.catch", Map.of()),
            node("schedule.every", Map.of("cron", "0 9 * * *")))))
            .isEqualTo(new TriggerNodeSchedule(TriggerType.WEBHOOK, null));
    }

    @Test
    @DisplayName("카탈로그의 모든 트리거 event는 자기 trigger_type으로 계산된다")
    void everyCatalogTriggerEvent_mapsToItsTriggerType() {
        List<EventDefinition> triggers = CATALOG.events().stream()
            .filter(e -> e.nodeType() == NodeType.TRIGGER).toList();
        assertThat(triggers).isNotEmpty();
        for (EventDefinition event : triggers) {
            assertThat(compute(List.of(node(event.name(), new HashMap<>()))).triggerType())
                .as(event.name()).isEqualTo(event.triggerType());
        }
    }

    @Test
    @DisplayName("과도기 find: TRIGGER 노드가 없으면 empty, 있으면 compute와 같다 (Task 3이 지운다)")
    void find_isEmptyOnlyWithoutTriggerNode() {
        assertThat(TriggerNodeSchedule.find(List.of(node("http.request", Map.of())), CATALOG)).isEmpty();
        assertThat(TriggerNodeSchedule.find(List.of(node("schedule.every", Map.of("cron", "0 9 * * *"))), CATALOG))
            .contains(new TriggerNodeSchedule(TriggerType.SCHEDULE, "0 9 * * *"));
    }
}
