package com.ieum.api.workflow.dto;

import com.ieum.workflowcore.domain.enums.NodeType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * 워크플로우 조회 응답의 노드. 요청 본문용 {@link NodeDto}와 분리돼 있다.
 *
 * <p>조회 원본인 Mongo {@code workflow_definitions.nodes}에는 {@link NodeDto}를 거치지 않는 저장
 * 경로가 있다 — {@code ChatService.saveWorkflowVersion}이 ieum-agent 응답의 노드를
 * {@code List<Map<String, Object>>}로 그대로 직렬화해 저장한다. 그래서 {@link NodeDto}에 붙은 Bean
 * Validation 어노테이션은 조회 응답에 대해서는 아무것도 보장하지 않는다(조회 경로는 {@code @Valid}를
 * 거치지 않는다).
 *
 * <p>이 타입은 raw {@code Map}에서 값을 직접 꺼내 만들어진다. Jackson 빈 바인딩 대신 값마다
 * {@code instanceof}로 타입을 확인한 뒤 읽으므로, 어긋난 값은 예외가 아니라 기본값이나 항목 제외로
 * 처리된다. 응답이 지키는 불변식은 {@link #fromDefinition} 한 곳에 모여 있다.
 *
 * <p>JSON 모양은 {@link NodeDto}와 동일하다 — 프론트 계약이 바뀌면 안 되기 때문이다.
 */
@Slf4j
public record NodeView(
    String id,
    NodeType type,
    String label,
    String description,
    Position position,
    @Schema(
        description = "노드 종류별 실행 설정. 값에는 문자열·숫자·boolean·배열·객체가 모두 올 수 있다"
            + " (예: HTTP의 method/url, CONDITION의 operator).",
        additionalProperties = Schema.AdditionalPropertiesValue.TRUE,
        example = "{\"method\": \"POST\", \"url\": \"https://example.com/hook\", \"headers\": {}}")
    Map<String, Object> config) {

    /** 좌표가 없는 노드에 조회 응답에서만 채워 주는 기본 배치값. */
    private static final double DEFAULT_X_ORIGIN = 40.0;
    private static final double DEFAULT_X_GAP = 380.0;
    private static final double DEFAULT_Y = 120.0;

    @Schema(description = "캔버스 좌표")
    public record Position(Double x, Double y) {}

    /**
     * Mongo 정의의 노드 배열을 응답용 노드 목록으로 옮긴다. 항목별 규칙은 다음과 같다.
     *
     * <ul>
     *   <li>{@code Map}이 아니거나 null인 원소 → 버린다.
     *   <li>{@code id} — 비어 있지 않은 문자열이어야 한다. 아니면 그 노드를 버린다. id 없는 노드는
     *       엣지가 가리킬 수도, 프론트가 식별할 수도 없다. 이 규칙 덕에 반환 목록의 id는 non-null이라
     *       {@link EdgeView#fromDefinition}의 id 집합에 null이 섞이지 않는다.
     *   <li>{@code type} — {@link NodeType} 값과 정확히 일치하면 enum, 아니면 null이고 <b>노드는
     *       남긴다</b>. 노드를 빼면 엣지가 가리키는 대상이 사라져 프론트 그래프가 더 크게 깨진다.
     *   <li>{@code label}·{@code description} — 문자열이면 그대로, 아니면 null.
     *   <li>{@code position} — {@code x}/{@code y}가 숫자면 쓰고, 아닌 축만 기본값으로 채운다.
     *   <li>{@code config} — {@code Map}이면 그대로, 아니면 빈 Map.
     * </ul>
     *
     * <p>기본 좌표와 빈 config는 <b>조회 응답 전용이다.</b> 좌표를 확정하는 주체는 프론트이므로
     * 이 값을 Mongo로 되쓰면 안 된다 — 되쓰는 순간 프론트가 확정한 좌표와 드리프트한다.
     */
    public static List<NodeView> fromDefinition(List<?> raw, UUID workflowId) {
        if (raw == null) {
            return List.of();
        }
        List<NodeView> nodes = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) {
                log.warn("[WORKFLOW_DEFINITION] 노드 1건 제외 — workflowId={}, cause=객체가 아닌 원소",
                    workflowId);
                continue;
            }
            String id = asString(map.get("id"));
            if (id == null || id.isBlank()) {
                log.warn("[WORKFLOW_DEFINITION] 노드 1건 제외 — workflowId={}, cause=id 없음",
                    workflowId);
                continue;
            }
            // 좌표 기본값의 인덱스는 응답에 남는 노드 기준이다 — 버려진 노드가 캔버스에 빈칸을
            // 남기지 않도록.
            double defaultX = DEFAULT_X_ORIGIN + nodes.size() * DEFAULT_X_GAP;
            nodes.add(new NodeView(
                id,
                asNodeType(map.get("type")),
                asString(map.get("label")),
                asString(map.get("description")),
                asPosition(map.get("position"), defaultX),
                asConfig(map.get("config"))));
        }
        return nodes;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    /** 알 수 없는 값은 null. 실행 엔진이 모르는 type을 enum으로 승격시키지 않는다. */
    private static NodeType asNodeType(Object value) {
        for (NodeType candidate : NodeType.values()) {
            if (candidate.name().equals(value)) {
                return candidate;
            }
        }
        return null;
    }

    private static Position asPosition(Object value, double defaultX) {
        Double x = null;
        Double y = null;
        if (value instanceof Map<?, ?> map) {
            x = asDouble(map.get("x"));
            y = asDouble(map.get("y"));
        }
        return new Position(x != null ? x : defaultX, y != null ? y : DEFAULT_Y);
    }

    private static Double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    /** Mongo 문서와 Jackson이 만드는 Map의 키는 문자열이라 캐스팅으로 충분하다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asConfig(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
