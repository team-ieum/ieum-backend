package com.ieum.workflowcore.engine;

import com.ieum.workflowcore.domain.enums.NodeType;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * 워크플로우 실행 중 현재 위치와 상태를 추적하는 커서.
 * 변수 치환({@code {{nodes.<id>.output.<field>}}})과 다음 노드 결정을 담당한다.
 */
@Slf4j
@Getter
@Setter
public class ExecutionCursor {

    /** {{nodes.<nodeId>.output.<fieldPath>}} 패턴 */
    private static final Pattern VARIABLE_PATTERN =
        Pattern.compile("\\{\\{nodes\\.([a-zA-Z0-9-]+)\\.output\\.([a-zA-Z0-9_.]+)\\}\\}");

    /** 무한 치환 루프 방지를 위한 최대 치환 반복 횟수 */
    private static final int MAX_RENDER_DEPTH = 5;

    private String currentNodeId;
    private ExecutionContext context;
    private final Set<String> visitedNodeIds = new HashSet<>();
    private List<Node> allNodes;
    private List<Edge> allEdges;

    // ──────────────────────────────────────────────────────────────────────
    // 노드 조회
    // ──────────────────────────────────────────────────────────────────────

    /** 현재 실행 위치의 노드 객체를 반환한다. 찾지 못하면 null. */
    public Node getCurrentNode() {
        if (currentNodeId == null) {
            return null;
        }
        return allNodes.stream()
            .filter(n -> n.getId().equals(currentNodeId))
            .findFirst()
            .orElse(null);
    }

    /**
     * 현재 노드 실행 완료 후 이동할 다음 노드를 결정한다.
     *
     * <p>CONDITION 노드인 경우 컨텍스트에 저장된 {@code result} 값(true/false)과
     * 엣지의 {@code conditionType}을 비교하여 분기 경로를 선택한다.
     *
     * @param currentNode 방금 실행을 마친 노드
     * @return 다음 노드, 없으면 null (마지막 노드)
     */
    public Node getNextNode(Node currentNode) {
        List<Edge> outgoingEdges = allEdges.stream()
            .filter(e -> e.getSource().equals(currentNode.getId()))
            .toList();

        if (outgoingEdges.isEmpty()) {
            log.debug("[Cursor] 노드 '{}' 이후 연결된 엣지 없음 — 실행 종료", currentNode.getId());
            return null;
        }

        Edge selectedEdge;

        if (currentNode.getType() == NodeType.CONDITION) {
            // 조건 노드: 실행 결과의 result 필드로 분기 방향 결정
            Object conditionResult = context.getNodeOutput(currentNode.getId()).get("result");
            String conditionType = Boolean.TRUE.equals(conditionResult) ? "true" : "false";

            log.debug("[Cursor] CONDITION 노드 '{}' 분기 방향: {}", currentNode.getId(), conditionType);

            selectedEdge = outgoingEdges.stream()
                .filter(e -> conditionType.equals(e.getConditionType()))
                .findFirst()
                .orElse(null);

            if (selectedEdge == null) {
                log.warn("[Cursor] CONDITION 노드 '{}'의 {} 경로에 연결된 엣지 없음",
                    currentNode.getId(), conditionType);
                return null;
            }
        } else {
            // 일반 노드: 첫 번째 엣지 선택 (단일 경로)
            selectedEdge = outgoingEdges.get(0);
        }

        return allNodes.stream()
            .filter(n -> n.getId().equals(selectedEdge.getTarget()))
            .findFirst()
            .orElse(null);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 변수 치환
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 입력 문자열에서 {@code {{nodes.<nodeId>.output.<fieldPath>}}} 패턴을 찾아
     * 실행 컨텍스트의 실제 값으로 치환한다.
     *
     * <p>중첩 치환(치환 결과에 다시 패턴이 포함된 경우)을 최대 {@value MAX_RENDER_DEPTH}회 반복한다.
     *
     * <p>fieldPath는 점(.)으로 구분된 중첩 키를 지원한다.
     * 예: {@code data.name} → output["data"]["name"]
     *
     * @param input 치환 대상 문자열
     * @return 치환이 완료된 문자열
     */
    public String renderVariables(String input) {
        if (input == null || !input.contains("{{")) {
            return input;
        }

        String result = input;
        for (int depth = 0; depth < MAX_RENDER_DEPTH; depth++) {
            String rendered = doRender(result);
            if (rendered.equals(result)) {
                break; // 더 이상 치환할 패턴 없음
            }
            result = rendered;
            log.debug("[Cursor] 변수 치환 depth={} 결과: {}", depth + 1, result);
        }
        return result;
    }

    private String doRender(String input) {
        Matcher matcher = VARIABLE_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String nodeId = matcher.group(1);
            String fieldPath = matcher.group(2);

            String replacement = resolveField(nodeId, fieldPath);
            log.debug("[Cursor] 치환: {{nodes.{}.output.{}}} → {}", nodeId, fieldPath, replacement);

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 컨텍스트에서 nodeId의 output 중 fieldPath에 해당하는 값을 반환한다.
     * fieldPath가 "a.b.c"인 경우 output["a"]["b"]["c"]를 탐색한다.
     */
    @SuppressWarnings("unchecked")
    private String resolveField(String nodeId, String fieldPath) {
        Map<String, Object> output = context.getNodeOutput(nodeId);
        if (output == null || output.isEmpty()) {
            log.warn("[Cursor] 노드 '{}'의 output이 비어 있음 — 변수를 빈 문자열로 치환", nodeId);
            return "";
        }

        String[] keys = fieldPath.split("\\.");
        Object current = output;

        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
            } else {
                if (current != null) {
                    log.debug("[Cursor] 노드 '{}' fieldPath '{}' 탐색 중 이미 최종 값에 도달하여 탐색을 조기 종료합니다.", nodeId, fieldPath);
                    return String.valueOf(current);
                }
                log.warn("[Cursor] 노드 '{}' fieldPath '{}' 탐색 중 Map이 아닌 값 만남: {}",
                    nodeId, fieldPath, current);
                return "";
            }
            if (current == null) {
                log.warn("[Cursor] 노드 '{}' fieldPath '{}' 에 해당하는 값 없음", nodeId, fieldPath);
                return "";
            }
        }

        return String.valueOf(current);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 컨텍스트 & 순환 참조
    // ──────────────────────────────────────────────────────────────────────

    /** 노드 실행 완료 후 결과를 컨텍스트에 저장한다. */
    public void updateContext(String nodeId, Map<String, Object> output) {
        context.setNodeOutput(nodeId, output);
        log.debug("[Cursor] 컨텍스트 업데이트 — nodeId: {}, outputKeys: {}",
            nodeId, output != null ? output.keySet() : "null");
    }

    /**
     * 다음 노드 이동 전 순환 참조 여부를 확인한다.
     * 순환이 아니면 방문 기록에 추가한다.
     *
     * @param nextNodeId 이동할 노드 ID
     * @return 이미 방문한 노드이면 true
     */
    public boolean isCircularReference(String nextNodeId) {
        if (visitedNodeIds.contains(nextNodeId)) {
            log.error("[Cursor] 순환 참조 감지 — nodeId: {}, visitedNodes: {}",
                nextNodeId, visitedNodeIds);
            return true;
        }
        visitedNodeIds.add(nextNodeId);
        return false;
    }
}
