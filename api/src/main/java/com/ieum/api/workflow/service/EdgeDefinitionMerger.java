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
 * <p>짝짓기는 두 갈래다.
 * <ol>
 *   <li><b>id 우선</b> — 요청 엣지에 id가 있으면 같은 id의 이전 엣지와 짝짓는다.
 *       {@code (source, target)}이 달라졌어도 같은 엣지다(FE가 엣지를 다른 노드로 옮긴 경우).</li>
 *   <li><b>(source, target) FIFO 폴백</b> — id가 없으면 키별 큐에서 등장 순서대로 집는다. 레거시
 *       정의와 ieum-agent가 만든 엣지에는 id가 없으므로 이 경로가 계속 필요하다. 요청이 값을
 *       보냈어도 큐는 하나 소비한다 — 안 그러면 같은 키의 다음 요청 엣지가 이미 짝지어진 이전
 *       엣지를 다시 집는다.</li>
 * </ol>
 * id로 짝지어진 이전 엣지는 FIFO 큐에서도 빠진다. 그래서 id 짝짓기를 먼저 한 바퀴 돌린 뒤에
 * 남은 자리만 FIFO로 채운다 — 요청에서 id 없는 엣지가 앞에 오더라도 뒤쪽 id 엣지의 짝을
 * 가로채지 못한다.
 *
 * <p>이어 붙이는 값은 {@code conditionType}과 짝지은 이전 엣지의 {@code id} 둘뿐이다. 노드 병합과
 * 달리 이전 엣지의 다른 키는 살리지 않는다. 짝을 못 찾으면 새 엣지로 본다.
 *
 * <p><b>출력은 요청 엣지와 1:1이고 순서도 같다.</b> {@code WorkflowService.withEdgeIds}가 이 성질에
 * 기대 인덱스로 id를 붙이므로, 요청 엣지를 거르거나 재정렬하면 id가 엉뚱한 엣지에 붙는다.
 *
 * <p>저장된 정의의 구조는 BE가 통제하지 못하므로 <b>이전 엣지</b>는 어떤 모양이 와도 예외를 만들지
 * 않고 조용히 무시한다. 반면 요청 엣지는 {@code @NotNull}·{@code @NotBlank}가 요청 경계에서 이미
 * 걸러 준다는 전제로 읽는다 — 그 검증을 떼면 여기서 NPE가 난다(id는 선택 필드라 없을 수 있다).
 */
final class EdgeDefinitionMerger {

    private EdgeDefinitionMerger() {
    }

    /**
     * @param previousEdges 직전 버전 정의의 edges (null 가능)
     * @param requestEdges  요청 edges. {@code @NotNull}이 리스트와 원소를 모두 강제하므로 null이 아니다
     * @return 요청 엣지와 1:1로, 요청 순서를 그대로 따르는 병합 결과. 원소는 가변 {@link Map}이다.
     *     요청에 없는 이전 엣지는 삭제된 것으로 본다
     */
    static List<Map<String, Object>> merge(List<Map<String, Object>> previousEdges,
        List<EdgeDto> requestEdges) {

        List<PreviousEdge> previous = readPrevious(previousEdges);
        Map<String, PreviousEdge> byId = new LinkedHashMap<>();
        Map<List<String>, Deque<PreviousEdge>> byEndpoints = new LinkedHashMap<>();
        for (PreviousEdge candidate : previous) {
            if (candidate.id != null) {
                // 중복 id는 거절하지 않는다(id는 클라이언트 소유) — 먼저 나온 것이 이긴다.
                byId.putIfAbsent(candidate.id, candidate);
            }
            byEndpoints.computeIfAbsent(List.of(candidate.source, candidate.target),
                key -> new LinkedList<>()).add(candidate);
        }

        PreviousEdge[] matches = new PreviousEdge[requestEdges.size()];
        // 1차: id로 짝짓는다. FIFO보다 먼저 돌아야 id 없는 요청 엣지가 짝을 가로채지 않는다.
        for (int i = 0; i < requestEdges.size(); i++) {
            String id = requestEdges.get(i).getId();
            if (id == null || id.isBlank()) {
                continue;
            }
            PreviousEdge candidate = byId.get(id);
            if (candidate != null && !candidate.matched) {
                candidate.matched = true;
                matches[i] = candidate;
            }
        }
        // 2차: id로 못 찾은 자리만 (source, target) FIFO로 채운다.
        for (int i = 0; i < requestEdges.size(); i++) {
            if (matches[i] == null) {
                EdgeDto edge = requestEdges.get(i);
                matches[i] = pollUnmatched(
                    byEndpoints.get(List.of(edge.getSource(), edge.getTarget())));
            }
        }

        List<Map<String, Object>> merged = new ArrayList<>(requestEdges.size());
        for (int i = 0; i < requestEdges.size(); i++) {
            EdgeDto edge = requestEdges.get(i);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", edge.getSource());
            result.put("target", edge.getTarget());
            // 짝지은 이전 엣지의 id를 그대로 실어 준다 — 요청이 id를 생략했을 때 withEdgeIds가 이
            // 값을 잇는다. 버리면 매 저장마다 새 UUID가 찍혀 id가 회차마다 갈리는 nonce가 된다.
            if (matches[i] != null && matches[i].id != null) {
                result.put("id", matches[i].id);
            }
            Object conditionType = edge.getConditionType() != null
                ? edge.getConditionType()
                : (matches[i] != null ? matches[i].conditionType : null);
            if (conditionType != null) {
                result.put("conditionType", conditionType);
            }
            merged.add(result);
        }
        return merged;
    }

    /** 큐 앞에서 아직 짝짓지 않은 이전 엣지를 하나 꺼낸다. id로 이미 집힌 것은 건너뛴다. */
    private static PreviousEdge pollUnmatched(Deque<PreviousEdge> queue) {
        if (queue == null) {
            return null;
        }
        while (!queue.isEmpty()) {
            PreviousEdge candidate = queue.poll();
            if (!candidate.matched) {
                candidate.matched = true;
                return candidate;
            }
        }
        return null;
    }

    /** 저장된 정의에서 짝짓기에 쓸 수 있는 이전 엣지만 등장 순서대로 읽는다. */
    private static List<PreviousEdge> readPrevious(List<Map<String, Object>> previousEdges) {
        if (previousEdges == null) {
            return List.of();
        }
        List<PreviousEdge> previous = new ArrayList<>();
        // Map으로 선언돼 있어도 실제 원소가 Map이라는 보장은 없다 — 제네릭이 지워진 자리라
        // for-each의 암묵 캐스트가 ClassCastException이 되고, 그대로 PUT 500이 된다.
        for (Object item : previousEdges) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            // 폴백 짝짓기 키가 문자열이 아니면 요청 엣지와 맞춰 볼 수 없으니 버린다.
            if (!(raw.get("source") instanceof String source)
                || !(raw.get("target") instanceof String target)) {
                continue;
            }
            // id는 선택 필드다 — 문자열이 아니거나 비어 있으면 없는 것으로 보고 FIFO만 쓴다.
            String id = raw.get("id") instanceof String value && !value.isBlank() ? value : null;
            // conditionType이 null인 이전 엣지도 담는다 — 큐 소비 순서가 어긋나면 안 된다.
            // 문자열이어야 할 자리에 boolean true가 저장되는 경로가 있어 값은 그대로 옮긴다.
            previous.add(new PreviousEdge(id, source, target, raw.get("conditionType")));
        }
        return previous;
    }

    /** 짝짓기 대상인 이전 엣지 하나. 두 갈래가 같은 엣지를 두 번 집지 않도록 소비 여부를 들고 있다. */
    private static final class PreviousEdge {

        private final String id;
        private final String source;
        private final String target;
        private final Object conditionType;
        private boolean matched;

        private PreviousEdge(String id, String source, String target, Object conditionType) {
            this.id = id;
            this.source = source;
            this.target = target;
            this.conditionType = conditionType;
        }
    }
}
