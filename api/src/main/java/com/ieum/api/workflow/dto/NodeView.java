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
     *   <li>{@code id} — {@link #asText} 규칙으로 읽히고 비어 있지 않아야 한다. 아니면 그 노드를
     *       버린다. id 없는 노드는 엣지가 가리킬 수도, 프론트가 식별할 수도 없다. 이 규칙 덕에 반환
     *       목록의 id는 non-null이라 {@link EdgeView#fromDefinition}의 id 집합에 null이 섞이지 않는다.
     *   <li>{@code type} — {@link NodeType} 값과 정확히 일치하면 enum, 아니면 null이고 <b>노드는
     *       남긴다</b>. 노드를 빼면 엣지가 가리키는 대상이 사라져 프론트 그래프가 더 크게 깨진다.
     *   <li>{@code label}·{@code description} — {@link #asText} 규칙(문자열·숫자·boolean은 받고 구조는
     *       버린다).
     *   <li>{@code position} — {@code x}/{@code y}가 {@link #asCoordinate}로 읽히면 쓰고, 아닌 축만
     *       기본값으로 채운다.
     *   <li>{@code config} — {@code Map}이면 그대로, 아니면 빈 Map.
     * </ul>
     *
     * <p>값이 있는데 위 규칙으로 쓸 수 없어 버리는 경우는 모두 경고 로그를 남긴다 — 소리 없이
     * 사라지면 좌표 리셋 같은 손실을 아무도 눈치채지 못한다. 값 자체는 사용자 데이터라 싣지 않고
     * 필드명과 사유만 남긴다.
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
            String id = asText(map.get("id"), "id", workflowId);
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
                asNodeType(map.get("type"), workflowId),
                asText(map.get("label"), "label", workflowId),
                asText(map.get("description"), "description", workflowId),
                asPosition(map.get("position"), defaultX, workflowId),
                asConfig(map.get("config"), workflowId)));
        }
        return nodes;
    }

    /**
     * 문자열로 쓸 값을 읽는다. 문자열은 그대로, 숫자·boolean은 문자열로 바꾼다 — 검증을 거치지 않는
     * 저장 경로에서 {@code label}이 숫자로 들어오는 일이 있고, 이전 매핑(Jackson
     * {@code convertValue})은 그런 값을 살렸다. {@code Map}·{@code List} 같은 구조는 문자열로 만들어야
     * 의미 있는 값이 나오지 않으므로 null로 버리고 경고만 남긴다.
     *
     * <p>{@link EdgeView}도 같은 규칙을 쓴다 — 노드 id는 스칼라를 받는데 엣지의 {@code source}는 안
     * 받으면 같은 문서에서 노드는 살고 그 노드의 엣지만 사라진다.
     */
    static String asText(Object value, String field, UUID workflowId) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        log.warn("[WORKFLOW_DEFINITION] 필드 1건 제외 — workflowId={}, field={}, cause=문자열로 쓸 수 없는 값",
            workflowId, field);
        return null;
    }

    /** 알 수 없는 값은 null. 실행 엔진이 모르는 type을 enum으로 승격시키지 않는다. */
    private static NodeType asNodeType(Object value, UUID workflowId) {
        for (NodeType candidate : NodeType.values()) {
            if (candidate.name().equals(value)) {
                return candidate;
            }
        }
        if (value != null) {
            // 값 자체(agent가 만든 type 토큰)는 싣지 않는다 — 다른 필드와 같은 기준.
            log.warn("[WORKFLOW_DEFINITION] 필드 1건 제외 — workflowId={}, field=type,"
                + " cause=enum에 없는 값", workflowId);
        }
        return null;
    }

    private static Position asPosition(Object value, double defaultX, UUID workflowId) {
        Double x = null;
        Double y = null;
        if (value instanceof Map<?, ?> map) {
            x = asCoordinate(map.get("x"), "position.x", workflowId);
            y = asCoordinate(map.get("y"), "position.y", workflowId);
        } else if (value != null) {
            log.warn("[WORKFLOW_DEFINITION] 필드 1건 제외 — workflowId={}, field=position, cause=객체가 아닌 값",
                workflowId);
        }
        return new Position(x != null ? x : defaultX, y != null ? y : DEFAULT_Y);
    }

    /**
     * 숫자면 그대로, 숫자로 읽히는 문자열이면 변환한다. 좌표를 놓치면 기본 배치로 리셋되고, 프론트가
     * 그 상태로 캔버스를 저장하면 원래 좌표가 정의에서 사라진다 — 되돌릴 수 없는 손실이라 이전
     * 매핑만큼 관대하게 받는다.
     *
     * <p>NaN·무한대는 표준 JSON 숫자가 아니라 제외한다. Jackson 기본 설정은 이 값을 예외 대신
     * 따옴표 붙은 {@code "NaN"} 문자열로 직렬화하므로 JSON 파싱이 깨지지는 않는다. 문제는 숫자여야
     * 할 좌표 자리에 문자열이 실려 프론트의 좌표 계산이 망가지는 것이다.
     */
    private static Double asCoordinate(Object value, String field, UUID workflowId) {
        if (value instanceof Number n && Double.isFinite(n.doubleValue())) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                double parsed = Double.parseDouble(s);
                if (Double.isFinite(parsed)) {
                    return parsed;
                }
            } catch (NumberFormatException e) {
                // 숫자가 아닌 문자열 — 아래 공통 경고로 떨어진다.
            }
        }
        if (value != null) {
            log.warn("[WORKFLOW_DEFINITION] 필드 1건 기본값 대체 — workflowId={}, field={},"
                + " cause=좌표로 읽을 수 없는 값", workflowId, field);
        }
        return null;
    }

    /** Mongo 문서와 Jackson이 만드는 Map의 키는 문자열이라 캐스팅으로 충분하다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asConfig(Object value, UUID workflowId) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (value != null) {
            log.warn("[WORKFLOW_DEFINITION] 필드 1건 제외 — workflowId={}, field=config, cause=객체가 아닌 값",
                workflowId);
        }
        return Map.of();
    }
}
