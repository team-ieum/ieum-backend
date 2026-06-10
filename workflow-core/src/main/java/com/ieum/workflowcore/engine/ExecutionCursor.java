package com.ieum.workflowcore.engine;

import com.ieum.workflowcore.domain.enums.NodeType;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * 워크플로우 실행 중 그래프 구조 조회와 변수 치환을 담당하는 커서.
 *
 * <p>fan-out 실행을 위해 단일 경로 순회({@code getNextNode}) 대신 그래프 헬퍼
 * ({@link #outgoingEdges}, {@link #incomingEdges}, {@link #liveOutgoingEdges})를 제공한다.
 * 실제 위상 스케줄링은 {@code SyncExecutionRuntime}이 담당한다.
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

    private ExecutionContext context;
    private List<Node> allNodes;
    private List<Edge> allEdges;

    // ──────────────────────────────────────────────────────────────────────
    // 그래프 조회 (fan-out 위상 스케줄링용)
    // ──────────────────────────────────────────────────────────────────────

    /** nodeId로 노드를 조회한다. 없으면 null. */
    public Node findNode(String nodeId) {
        return allNodes.stream()
            .filter(n -> n.getId().equals(nodeId))
            .findFirst()
            .orElse(null);
    }

    /** 해당 노드에서 나가는 outgoing 엣지 목록. */
    public List<Edge> outgoingEdges(String nodeId) {
        return allEdges.stream()
            .filter(e -> e.getSource().equals(nodeId))
            .toList();
    }

    /** 해당 노드로 들어오는 incoming 엣지 목록. */
    public List<Edge> incomingEdges(String nodeId) {
        return allEdges.stream()
            .filter(e -> e.getTarget().equals(nodeId))
            .toList();
    }

    /**
     * 방금 실행을 마친 노드의 '살아있는(live)' outgoing 엣지를 반환한다.
     *
     * <p>CONDITION 노드는 컨텍스트의 {@code result}(true/false)와 일치하는 conditionType 엣지만 live이고,
     * 그 외 노드는 모든 outgoing 엣지가 live다. 죽은(dead) 엣지의 타깃은 스케줄러가 가지치기한다.
     */
    public List<Edge> liveOutgoingEdges(Node node) {
        List<Edge> outgoing = outgoingEdges(node.getId());
        if (node.getType() != NodeType.CONDITION) {
            return outgoing;
        }
        Object conditionResult = context.getNodeOutput(node.getId()).get("result");
        String conditionType = Boolean.TRUE.equals(conditionResult) ? "true" : "false";
        log.debug("[Cursor] CONDITION 노드 '{}' 분기 방향: {}", node.getId(), conditionType);
        return outgoing.stream()
            .filter(e -> conditionType.equals(e.getConditionType()))
            .toList();
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
}
