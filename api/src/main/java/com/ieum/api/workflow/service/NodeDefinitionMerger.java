package com.ieum.api.workflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.workflow.dto.NodeDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 수정 요청의 노드를 직전 버전 정의 위에 덮어써, 요청이 보내지 않은 필드가 지워지는 것을 막는다
 * (IEUM-BE-65).
 *
 * <p>저장된 정의의 구조는 BE가 통제하지 못한다(agent가 쓴 추가 필드, 과거 스키마 등). 그래서 이
 * 함수는 어떤 입력에도 예외를 던지지 않고, 해석할 수 없는 이전 노드는 조용히 무시한다.
 */
final class NodeDefinitionMerger {

    private static final TypeReference<Map<String, Object>> NODE_MAP = new TypeReference<>() {
    };

    private NodeDefinitionMerger() {
    }

    /**
     * @param previousNodes 직전 버전 정의의 nodes (null 가능)
     * @param requestNodes  요청 nodes (null 가능)
     * @return 요청 순서를 따르는 병합 결과. 요청에 없는 이전 노드는 삭제된 것으로 본다
     */
    static List<Map<String, Object>> merge(List<Map<String, Object>> previousNodes,
        List<NodeDto> requestNodes, ObjectMapper objectMapper) {

        if (requestNodes == null) {
            return List.of();
        }

        Map<String, Map<String, Object>> previousById = indexById(previousNodes);
        List<Map<String, Object>> merged = new ArrayList<>(requestNodes.size());

        for (NodeDto node : requestNodes) {
            Map<String, Object> requested = objectMapper.convertValue(node, NODE_MAP);
            // 값이 null인 필드는 "변경 없음"이다 — 이전 값을 덮어쓰지 않게 뺀다.
            requested.values().removeIf(Objects::isNull);

            Map<String, Object> previous = previousById.get(node.getId());
            if (previous == null) {
                merged.add(requested);
                continue;
            }
            // 얕은 복사본을 베이스로 둬야 원본 정의가 바뀌지 않는다.
            Map<String, Object> base = new LinkedHashMap<>(previous);
            base.putAll(requested);
            merged.add(base);
        }
        return merged;
    }

    private static Map<String, Map<String, Object>> indexById(
        List<Map<String, Object>> previousNodes) {

        if (previousNodes == null) {
            return Map.of();
        }
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        for (Map<String, Object> node : previousNodes) {
            if (node == null) {
                continue;
            }
            // id가 없거나 문자열이 아닌 항목은 짝지을 수 없으니 버린다. 중복이면 먼저 나온 것을 쓴다.
            if (node.get("id") instanceof String id) {
                index.putIfAbsent(id, node);
            }
        }
        return index;
    }
}
