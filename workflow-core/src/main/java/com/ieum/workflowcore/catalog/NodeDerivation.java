package com.ieum.workflowcore.catalog;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.domain.enums.NodeType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 노드 정의(Map)를 카탈로그 event 기준으로 계산한다. 노드가 들어오는 네 경로(REST 생성·수정, 채팅 저장, 노드 테스트
 * 초안)가 모두 이 클래스를 거친다(IEUM-BE-92). event가 노드의 유일한 정체성이므로 type과 비AI 노드의 brand는
 * event에서 계산한 값으로 덮는다.
 *
 * <p>AI 노드의 brand는 덮지 않는다. agent가 도구를 보고 정한 값(github·notion 등)이 연동 목록 조회
 * ({@code nodes.config.brand})의 근거다. 이것을 {@code apps.json}의 {@code ai.brand}로 덮으면 해당 노드가 연동 목록에서 빠진다.
 */
public final class NodeDerivation {

    private final EventCatalog catalog;

    public NodeDerivation(EventCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * @param node 노드 정의. 이 메서드는 입력을 바꾸지 않는다
     * @return 새 Map. type은 event의 node_type이다. config는 변경 가능한 LinkedHashMap 사본으로,
     *         triggerType·serviceType이 지워지고 ACTION이면 tools도 지워진다. 비AI 노드는 brand가 앱 brand로 덮인다
     * @throws CustomException INVALID_WORKFLOW: event가 없거나 카탈로그에 없는 값일 때, 또는 실행할 수 없는 이벤트
     *         (ACTION인데 builder가 아님, 그 밖은 builder·generation 둘 다 false — agent {@code /v1/actions/execute}가
     *         builder ACTION의 tool_key만 받는다)일 때
     */
    public Map<String, Object> derive(Map<String, Object> node) {
        Object event = node.get("event");
        EventDefinition definition = event instanceof String name ? catalog.event(name).orElse(null) : null;
        if (definition == null || !executable(definition)) {
            throw new CustomException(ErrorCode.INVALID_WORKFLOW,
                "알 수 없는 노드 종류입니다: " + echo(event) + " (노드 " + echo(node.get("id")) + ")");
        }
        Map<String, Object> config = new LinkedHashMap<>();
        if (node.get("config") instanceof Map<?, ?> raw) {
            raw.forEach((key, value) -> config.put(String.valueOf(key), value));
        }
        config.remove("triggerType");
        config.remove("serviceType");
        if (definition.nodeType() == NodeType.ACTION) {
            config.remove("tools");
        }
        if (definition.nodeType() != NodeType.AI) {
            catalog.app(definition.app()).map(AppDefinition::brand)
                .ifPresent(brand -> config.put("brand", brand));
        }
        Map<String, Object> derived = new LinkedHashMap<>(node);
        derived.put("type", definition.nodeType().name());
        derived.put("config", config);
        return derived;
    }

    public List<Map<String, Object>> deriveAll(List<Map<String, Object>> nodes) {
        return nodes.stream().map(this::derive).toList();
    }

    /**
     * agent {@code /v1/actions/execute}는 builder ACTION의 tool_key만 받는다. 그래서 ACTION은 builder여야 하고,
     * 그 밖은 builder·generation 중 하나면 된다.
     */
    private static boolean executable(EventDefinition definition) {
        return definition.nodeType() == NodeType.ACTION
            ? definition.builder()
            : definition.builder() || definition.generation();
    }

    /** 제어 문자(개행 등)를 '?'로 바꾼 뒤 64자에서 자른다 — 요청 값이 메시지·로그 줄을 흔들지 못하게. */
    public static String echo(Object value) {
        String text = String.valueOf(value).replaceAll("\\p{Cntrl}", "?");
        return text.length() > 64 ? text.substring(0, 64) : text;
    }
}
