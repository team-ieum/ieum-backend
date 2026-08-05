package com.ieum.api.workflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * 워크플로우 조회 응답의 엣지. 요청 본문용 {@link EdgeDto}와 분리돼 있다.
 *
 * <p>분리 이유와 raw {@code Map}에서 직접 읽는 이유는 {@link NodeView} 참조. JSON 모양은
 * {@link EdgeDto}와 동일하다.
 */
@Slf4j
public record EdgeView(
    String source,
    String target,
    @Schema(description = "CONDITION 노드의 분기 값(\"true\"/\"false\"). 일반 연결에서는 비어 있다.")
    String conditionType) {

    /**
     * Mongo 정의의 엣지 배열을 응답용 엣지 목록으로 옮긴다. 항목별 규칙은 다음과 같다.
     *
     * <ul>
     *   <li>{@code Map}이 아니거나 null인 원소 → 버린다.
     *   <li>{@code source}·{@code target} — 둘 다 {@link NodeView#asText} 규칙으로 읽히고 비어 있지
     *       않아야 한다. 아니면 버린다.
     *   <li>{@code nodes}에 없는 id를 가리키는 엣지 → 버린다.
     *   <li>{@code conditionType} — {@link NodeView#asText} 규칙. 문자열 {@code "true"}가 와야 할
     *       자리에 boolean {@code true}가 오는 저장 경로가 있어 스칼라를 받는다.
     * </ul>
     *
     * <p>끊긴 엣지를 버리는 것은 <b>조회 응답 전용이다</b> — 저장된 정의는 그대로 두므로 원인 문서를
     * 고치면 엣지도 돌아온다. 프론트(React Flow)는 끊긴 엣지를 렌더하지 않고 콘솔 경고만 남기지만,
     * 그 상태로 캔버스를 저장하면 끊긴 참조가 정의에 되쓰인다. "응답의 엣지는 응답의 노드만
     * 가리킨다"를 서버가 지켜 두는 편이 프론트가 매 화면에서 방어하는 것보다 싸다.
     */
    public static List<EdgeView> fromDefinition(List<?> raw, List<NodeView> nodes, UUID workflowId) {
        if (raw == null) {
            return List.of();
        }
        // NodeView.fromDefinition이 id 없는 노드를 버리므로 이 집합에는 null이 들어가지 않는다.
        Set<String> nodeIds = new HashSet<>();
        for (NodeView node : nodes) {
            nodeIds.add(node.id());
        }
        List<EdgeView> edges = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) {
                log.warn("[WORKFLOW_DEFINITION] 엣지 1건 제외 — workflowId={}, cause=객체가 아닌 원소",
                    workflowId);
                continue;
            }
            String source = asNodeId(map.get("source"), "source", workflowId);
            String target = asNodeId(map.get("target"), "target", workflowId);
            // 지금은 아래 끊긴 엣지 정리가 이 항목들도 걸러낸다(노드 id는 비어 있지 않은 문자열이라
            // 집합이 null·공백을 담지 않는다). 그래도 남겨 두는 이유는, 이 검사가 응답 필드를
            // 문자열로 확정하는 지점이고 로그에 원인을 구분해 남기기 때문이다.
            if (source == null || target == null) {
                log.warn("[WORKFLOW_DEFINITION] 엣지 1건 제외 — workflowId={}, cause=source/target 없음",
                    workflowId);
                continue;
            }
            if (!nodeIds.contains(source) || !nodeIds.contains(target)) {
                // source·target은 노드 식별자라 사용자 데이터가 아니다.
                log.warn("[WORKFLOW_DEFINITION] 끊긴 엣지 1건 제외 — workflowId={}, source={}, target={}",
                    workflowId, source, target);
                continue;
            }
            edges.add(new EdgeView(source, target,
                NodeView.asText(map.get("conditionType"), "conditionType", workflowId)));
        }
        return edges;
    }

    private static String asNodeId(Object value, String field, UUID workflowId) {
        String text = NodeView.asText(value, field, workflowId);
        return text != null && !text.isBlank() ? text : null;
    }
}
