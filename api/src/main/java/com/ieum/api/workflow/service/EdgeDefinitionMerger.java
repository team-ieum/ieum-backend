package com.ieum.api.workflow.service;

import com.ieum.api.workflow.dto.EdgeDto;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 수정 요청의 엣지에 직전 버전의 {@code conditionType}을 이어 붙인다 (IEUM-BE-65).
 *
 * <p>{@code conditionType}은 선택 필드라 CONDITION 노드의 분기 엣지에서 클라이언트가 이 값을
 * 생략하고 PUT 하면 저장된 {@code "true"}/{@code "false"}가 지워진다. 그러면 실행 시
 * {@code ExecutionCursor.liveOutgoingEdges}가 걸러 낼 엣지가 하나도 남지 않아 조건 노드
 * 이후가 조용히 실행되지 않는다.
 *
 * <p>엣지에는 id가 없어 짝짓기 키는 {@code (source, target)}이고, CONDITION 두 분기가 같은 노드로
 * 합류하면 그 키가 겹친다. 그래서 키별 FIFO 큐로 등장 순서대로 짝짓는다. 요청이 값을 보냈으면 그
 * 값이 이기지만 큐는 그때도 하나 소비한다 — 안 그러면 같은 키의 다음 요청 엣지가 이미 짝지어진
 * 이전 엣지를 다시 집는다.
 *
 * <p>노드 병합과 달리 이전 엣지의 다른 키는 살리지 않는다. 엣지는 {@code source}·{@code target}·
 * {@code conditionType} 셋뿐이고 앞의 둘은 키라, 이을 값은 {@code conditionType} 하나다.
 *
 * <p>저장된 정의의 구조는 BE가 통제하지 못하므로 <b>이전 엣지</b>는 어떤 모양이 와도 예외를 만들지
 * 않고 조용히 무시한다. 반면 요청 엣지는 {@code @NotNull}·{@code @NotBlank}가 요청 경계에서 이미
 * 걸러 준다는 전제로 읽는다 — 그 검증을 떼면 여기서 NPE가 난다.
 */
final class EdgeDefinitionMerger {

    private EdgeDefinitionMerger() {
    }

    /**
     * @param previousEdges 직전 버전 정의의 edges (null 가능)
     * @param requestEdges  요청 edges. {@code @NotNull}이 리스트와 원소를 모두 강제하므로 null이 아니다
     * @return 요청 순서를 따르는 병합 결과. 요청에 없는 이전 엣지는 삭제된 것으로 본다
     */
    static List<Map<String, Object>> merge(List<Map<String, Object>> previousEdges,
        List<EdgeDto> requestEdges) {

        Map<List<String>, Deque<Object>> previousByEndpoints = indexByEndpoints(previousEdges);
        List<Map<String, Object>> merged = new ArrayList<>(requestEdges.size());

        for (EdgeDto edge : requestEdges) {
            Deque<Object> queue =
                previousByEndpoints.get(List.of(edge.getSource(), edge.getTarget()));
            // 요청이 값을 보냈든 아니든 큐는 하나 소비한다(같은 키의 다음 엣지가 재사용하지 않도록).
            Object previousConditionType = queue != null && !queue.isEmpty() ? queue.poll() : null;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", edge.getSource());
            result.put("target", edge.getTarget());
            Object conditionType = edge.getConditionType() != null
                ? edge.getConditionType() : previousConditionType;
            if (conditionType != null) {
                result.put("conditionType", conditionType);
            }
            merged.add(result);
        }
        return merged;
    }

    /** {@code (source, target)} → 등장 순서대로 담긴 이전 {@code conditionType} 큐. */
    private static Map<List<String>, Deque<Object>> indexByEndpoints(
        List<Map<String, Object>> previousEdges) {

        if (previousEdges == null) {
            return Map.of();
        }
        Map<List<String>, Deque<Object>> index = new LinkedHashMap<>();
        // Map으로 선언돼 있어도 실제 원소가 Map이라는 보장은 없다 — 제네릭이 지워진 자리라
        // for-each의 암묵 캐스트가 ClassCastException이 되고, 그대로 PUT 500이 된다.
        for (Object item : previousEdges) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            // 짝짓기 키가 문자열이 아니면 요청 엣지와 맞춰 볼 수 없으니 버린다.
            if (!(raw.get("source") instanceof String source)
                || !(raw.get("target") instanceof String target)) {
                continue;
            }
            // conditionType이 null인 이전 엣지도 담는다 — 큐 소비 순서가 어긋나면 안 된다.
            // 문자열이어야 할 자리에 boolean true가 저장되는 경로가 있어 값은 그대로 옮긴다.
            index.computeIfAbsent(List.of(source, target), key -> new LinkedList<>())
                .add(raw.get("conditionType"));
        }
        return index;
    }
}
