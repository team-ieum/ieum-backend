package com.ieum.workflowcore.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.enums.NodeType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** event → type·brand 계산과 옛 키 삭제 (IEUM-BE-92). 이벤트 이름·개수는 카탈로그에서 꺼낸다(하드코딩 금지). */
class NodeDerivationTest {

    private static final EventCatalog CATALOG = EventCatalog.loadFromClasspath();
    private final NodeDerivation derivation = new NodeDerivation(CATALOG);

    private static Map<String, Object> node(Object event, Object config) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "node-1");
        node.put("event", event);
        node.put("type", "HTTP"); // 요청 type — 무시돼야 한다
        node.put("label", "노드");
        node.put("config", config);
        return node;
    }

    /** 실행 가능한 이벤트 — ACTION은 builder여야 하고, 그 밖은 builder·generation 중 하나다. 운영 규칙(NodeDerivation)과 같다. */
    private static boolean executable(EventDefinition event) {
        return event.nodeType() == NodeType.ACTION ? event.builder() : (event.builder() || event.generation());
    }

    private static EventDefinition firstOf(NodeType type) {
        return CATALOG.events().stream().filter(e -> e.nodeType() == type && executable(e)).findFirst()
            .orElseThrow(() -> new AssertionError("카탈로그에 실행 가능한 " + type + " 이벤트가 없다"));
    }

    private static String brandOf(EventDefinition event) {
        return CATALOG.app(event.app()).orElseThrow().brand();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> config(Map<String, Object> derived) {
        return (Map<String, Object>) derived.get("config");
    }

    @Test
    @DisplayName("모든 카탈로그 이벤트: type은 요청 값이 아니라 event의 node_type이다")
    void typeComesFromEvent() {
        assertThat(CATALOG.events()).isNotEmpty();
        for (EventDefinition event : CATALOG.events()) {
            if (!executable(event)) {
                continue;
            }
            Map<String, Object> derived = derivation.derive(node(event.name(), new HashMap<>()));
            assertThat(derived.get("type")).as(event.name()).isEqualTo(event.nodeType().name());
            assertThat(derived.get("event")).isEqualTo(event.name());
        }
    }

    @Test
    @DisplayName("AI가 아닌 노드의 brand는 요청 값과 무관하게 앱 brand로 덮인다")
    void nonAiBrandComesFromApp() {
        for (EventDefinition event : CATALOG.events()) {
            if (event.nodeType() == NodeType.AI || !executable(event)) {
                continue;
            }
            Map<String, Object> derived = derivation.derive(node(event.name(), Map.of("brand", "evil")));
            assertThat(config(derived)).as(event.name()).containsEntry("brand", brandOf(event));
        }
    }

    @Test
    @DisplayName("AI 노드의 brand는 그대로 둔다 — 없으면 넣지 않는다")
    void aiBrandIsKept() {
        String ai = firstOf(NodeType.AI).name();
        assertThat(config(derivation.derive(node(ai, Map.of("brand", "notion")))))
            .containsEntry("brand", "notion");
        assertThat(config(derivation.derive(node(ai, Map.of())))).doesNotContainKey("brand");
    }

    @Test
    @DisplayName("옛 키 triggerType·serviceType은 지우고 입력 칸은 남긴다")
    void legacyKeysAreRemoved() {
        Map<String, Object> derived = derivation.derive(node(firstOf(NodeType.TRIGGER).name(),
            Map.of("triggerType", "SCHEDULE", "serviceType", "GOOGLE", "cron", "0 9 * * *")));
        assertThat(config(derived)).containsEntry("cron", "0 9 * * *")
            .doesNotContainKeys("triggerType", "serviceType");
    }

    @Test
    @DisplayName("ACTION은 config.tools를 지우고, AI는 tools를 남긴다")
    void toolsRemovedOnlyForAction() {
        List<Map<String, Object>> tools = List.of(Map.of("name", "builtin:x"));
        assertThat(config(derivation.derive(node(firstOf(NodeType.ACTION).name(),
            Map.of("tools", tools, "owner", "ieum")))))
            .containsEntry("owner", "ieum").doesNotContainKey("tools");
        assertThat(config(derivation.derive(node(firstOf(NodeType.AI).name(), Map.of("tools", tools)))))
            .containsEntry("tools", tools);
    }

    @Test
    @DisplayName("config가 없거나 Map이 아니면 빈 config에서 시작한다")
    void missingOrNonMapConfig() {
        String ai = firstOf(NodeType.AI).name();
        assertThat(config(derivation.derive(node(ai, null)))).isEmpty();
        assertThat(config(derivation.derive(node(ai, "문자열")))).isEmpty();
    }

    @Test
    @DisplayName("입력 노드·config는 바뀌지 않고, 결과 config는 변경 가능한 사본이다")
    void inputIsNotMutated() {
        Map<String, Object> inputConfig = new HashMap<>(Map.of("triggerType", "WEBHOOK"));
        Map<String, Object> input = node(firstOf(NodeType.TRIGGER).name(), inputConfig);

        Map<String, Object> derived = derivation.derive(input);

        assertThat(input).containsEntry("type", "HTTP");
        assertThat(inputConfig).containsOnlyKeys("triggerType");
        assertThat(derived.get("config")).isNotSameAs(inputConfig);
        config(derived).put("credentialId", "x"); // 채팅 경로가 주입한다
        assertThat(config(derived)).containsEntry("credentialId", "x");
    }

    @Test
    @DisplayName("id·label·description·position 같은 다른 키는 그대로 옮긴다")
    void otherKeysArePreserved() {
        Map<String, Object> input = node(firstOf(NodeType.AI).name(), Map.of());
        input.put("description", "설명");
        input.put("position", Map.of("x", 1, "y", 2));

        assertThat(derivation.derive(input))
            .containsEntry("id", "node-1").containsEntry("label", "노드")
            .containsEntry("description", "설명").containsEntry("position", Map.of("x", 1, "y", 2));
    }

    @Test
    @DisplayName("event가 없거나 문자열이 아니거나 카탈로그에 없으면 INVALID_WORKFLOW — 메시지에 event·노드 id")
    void unknownEventIsRejected() {
        for (Object event : Arrays.asList(null, "", "github.delete_repo", 123)) {
            assertThatThrownBy(() -> derivation.derive(node(event, Map.of())))
                .as(String.valueOf(event))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW);
                    assertThat(e.getMessage()).contains(String.valueOf(event)).contains("node-1");
                });
        }
    }

    @Test
    @DisplayName("프리셋 id는 event가 아니다 — 이벤트 이름과 겹치지 않는 프리셋 id는 400")
    void presetIdIsNotAnEvent() {
        for (PresetDefinition preset : CATALOG.presets()) {
            if (CATALOG.event(preset.id()).isPresent()) {
                continue;
            }
            assertThatThrownBy(() -> derivation.derive(node(preset.id(), Map.of())))
                .as(preset.id()).isInstanceOf(CustomException.class);
        }
    }

    @Test
    @DisplayName("메시지의 event·노드 id는 64자에서 자른다")
    void echoIsClipped() {
        Map<String, Object> input = node("e".repeat(100), Map.of());
        input.put("id", "i".repeat(100));

        assertThatThrownBy(() -> derivation.derive(input))
            .hasMessageContaining("e".repeat(64)).hasMessageNotContaining("e".repeat(65))
            .hasMessageContaining("i".repeat(64)).hasMessageNotContaining("i".repeat(65));
    }

    @Test
    @DisplayName("메시지의 제어 문자(개행 등)는 '?'로 바꿔 싣는다 — 로그·응답 줄 주입 방지")
    void echoReplacesControlCharacters() {
        assertThatThrownBy(() -> derivation.derive(node("a\nb", Map.of())))
            .hasMessageContaining("a?b").hasMessageNotContaining("\n");
    }

    @Test
    @DisplayName("실행할 수 없는 이벤트(ACTION은 builder 아님, 그 밖은 builder·generation 둘 다 false)는 모르는 event와 같은 400 — agent가 실행할 수 없다")
    void unexecutableEventIsRejected() {
        // agent /v1/actions/execute는 builder ACTION의 tool_key만 받는다. 이름은 카탈로그에서 고른다(하드코딩 금지).
        List<EventDefinition> targets = CATALOG.events().stream().filter(e -> !executable(e)).toList();
        assumeThat(targets).as("실행 불가 이벤트가 카탈로그에 없다").isNotEmpty();
        for (EventDefinition event : targets) {
            assertThatThrownBy(() -> derivation.derive(node(event.name(), Map.of())))
                .as(event.name())
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_WORKFLOW);
                    assertThat(e.getMessage()).contains(event.name()).contains("node-1");
                });
        }
    }

    @Test
    @DisplayName("deriveAll은 순서를 지키고, 하나라도 모르는 event면 전체를 거부한다")
    void deriveAllKeepsOrder() {
        String trigger = firstOf(NodeType.TRIGGER).name();
        String ai = firstOf(NodeType.AI).name();

        assertThat(derivation.deriveAll(List.of(node(trigger, Map.of()), node(ai, Map.of()))))
            .extracting(n -> n.get("event")).containsExactly(trigger, ai);
        assertThatThrownBy(() -> derivation.deriveAll(List.of(node(ai, Map.of()), node("nope", Map.of()))))
            .isInstanceOf(CustomException.class);
    }
}
