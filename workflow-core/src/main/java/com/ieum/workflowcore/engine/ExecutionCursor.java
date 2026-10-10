package com.ieum.workflowcore.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.workflowcore.domain.enums.NodeType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 참조 대상이 Map·List일 때 JSON 문자열로 바꾸는 데 쓴다. 스레드 안전하다. */
    private static final ObjectMapper JSON = new ObjectMapper();

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
     * <p>단일 패스다 — 치환 결과(이슈 제목·메일 본문 같은 외부 데이터)에 든 참조식 리터럴은 다시 치환하지 않는다.
     * 재치환하면 외부 텍스트가 같은 실행의 다른 노드 출력을 끌어와 밖으로 내보낼 수 있다.
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
        return doRender(input);
    }

    /**
     * Map·List를 재귀로 돌며 모든 String 값을 {@link #renderVariables}로 치환한 <b>가변 복사본</b>을 돌려준다.
     * 원본은 건드리지 않는다 — {@code AgentNodeExecutor}가 tools 복사본에 웹훅 URL 원문을 넣으므로,
     * 원본을 쓰면 그 원문이 실행 중 공유되는 노드 객체에 남는다. 그 외 타입은 그대로 돌려준다.
     */
    public Object renderDeep(Object value) {
        if (value instanceof String s) {
            return renderVariables(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), renderDeep(v)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(v -> copy.add(renderDeep(v)));
            return copy;
        }
        return value;
    }

    /**
     * 값 안의 문자열들이 <b>직접</b> 참조하는 노드 id를 등장 순서·중복 없이 모은다 — 노드 단일 테스트가
     * 어떤 앞 노드의 샘플을 컨텍스트에 넣어야 하는지 정하는 근거다. 치환은 단일 패스라 치환 결과 안의
     * 참조식은 보지 않는다. Map 키는 {@link #renderDeep}과 같이 훑지 않는다.
     */
    public static Set<String> referencedNodeIds(Object value) {
        Set<String> ids = new LinkedHashSet<>();
        collectReferencedNodeIds(value, ids);
        return ids;
    }

    private static void collectReferencedNodeIds(Object value, Set<String> ids) {
        if (value instanceof String s) {
            Matcher matcher = VARIABLE_PATTERN.matcher(s);
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collectReferencedNodeIds(v, ids));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> collectReferencedNodeIds(v, ids));
        }
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
     *
     * <p>값이 List면 숫자 세그먼트는 인덱스다 — {@code issues.0.title}은 {@code output["issues"][0]["title"]}.
     * 범위 밖이면 없는 값과 같이 빈 문자열이다. 최종 값이 Map·List면 JSON 문자열로, 그 외는 {@code String.valueOf}다.
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
            } else if (current instanceof List<?> list && isIndex(key)) {
                current = elementAt(list, key);
            } else {
                if (current != null) {
                    log.debug("[Cursor] 노드 '{}' fieldPath '{}' 탐색 중 이미 최종 값에 도달하여 탐색을 조기 종료합니다.", nodeId, fieldPath);
                    return stringify(current);
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

        return stringify(current);
    }

    private static boolean isIndex(String key) {
        return !key.isEmpty() && key.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /** 범위 밖이거나 int 범위를 넘는 인덱스는 null — 호출부가 "값 없음"으로 처리한다. */
    private static Object elementAt(List<?> list, String key) {
        try {
            int index = Integer.parseInt(key);
            return index < list.size() ? list.get(index) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Map·List는 JSON으로(직렬화가 안 되면 toString), 그 외는 {@code String.valueOf}. */
    private static String stringify(Object value) {
        if (value instanceof Map || value instanceof List) {
            try {
                return JSON.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
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
