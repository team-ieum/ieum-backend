package com.ieum.api.workflow.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ieum.workflowcore.domain.enums.NodeType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
public class NodeDto {

    /** 좌표가 없는 노드에 조회 응답에서만 채워 주는 기본 배치값. */
    private static final double DEFAULT_X_ORIGIN = 40.0;
    private static final double DEFAULT_X_GAP = 380.0;
    private static final double DEFAULT_Y = 120.0;

    @NotBlank(message = "노드 id는 필수입니다.")
    private String id;

    /**
     * 알 수 없는 값은 역직렬화 단계에서 예외 대신 null로 흘린다.
     *
     * <p>이 DTO는 요청 본문뿐 아니라 <b>조회 응답</b>에도 쓰이는데, 조회 쪽 원본인 Mongo
     * {@code workflow_definitions.nodes}는 이 DTO를 거치지 않고 저장되는 경로(ieum-agent 생성분)가
     * 있어 BE가 값을 통제하지 못한다. 예외로 두면 어긋난 문서 하나가 목록 조회 전체를 500으로
     * 무너뜨린다. 요청 경로에서는 null이 된 값을 아래 {@code @NotNull}이 400으로 잡는다.
     */
    @NotNull(message = "노드 type은 필수입니다.")
    @JsonFormat(with = JsonFormat.Feature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
    private NodeType type;

    @NotBlank(message = "노드 label은 필수입니다.")
    private String label;

    /** 일반 사용자에게 보여줄 자연어 설명. 개발자용 기술 설명이 아니다. */
    @NotBlank(message = "노드 description은 필수입니다.")
    private String description;

    /** 캔버스 위치. 좌표를 확정하는 주체는 프론트고, 서버는 저장·반환만 한다. */
    @NotNull(message = "노드 position은 필수입니다.")
    @Valid
    private Position position;

    /** 노드 종류별 실행 설정 (프롬프트, URL, 조건식 등). */
    @Schema(
        description = "노드 종류별 실행 설정. 값에는 문자열·숫자·boolean·배열·객체가 모두 올 수 있다"
            + " (예: HTTP의 method/url, CONDITION의 operator).",
        additionalProperties = Schema.AdditionalPropertiesValue.TRUE,
        example = "{\"method\": \"POST\", \"url\": \"https://example.com/hook\", \"headers\": {}}")
    private Map<String, Object> config;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "캔버스 좌표")
    public static class Position {

        @NotNull(message = "position.x는 필수입니다.")
        private Double x;

        @NotNull(message = "position.y는 필수입니다.")
        private Double y;
    }

    /**
     * 좌표가 없는 노드에 기본 배치값을 채운다. <b>조회 응답 전용이다.</b>
     *
     * <p>좌표를 확정하는 주체는 프론트이므로 이 값을 Mongo로 되쓰면 안 된다 — 되쓰는 순간
     * 프론트가 확정한 좌표와 드리프트한다. 저장된 정의에 좌표가 없는 경우는 두 가지다:
     * 이 필드가 생기기 전에 만들어진 워크플로우, 그리고 ieum-agent가 생성한 워크플로우
     * (agent 응답은 이 DTO를 타지 않고 그대로 저장된다).
     *
     * <p>{@code position} 자체가 없는 경우뿐 아니라 {@code x}·{@code y} 한쪽만 있는 경우도 채운다.
     * 계약상 좌표는 null일 수 없는데, 검증을 거치지 않는 위 저장 경로에서는 부분 좌표가 들어올 수 있다.
     *
     * <p>노드 목록이 없는 정의도 정상 상태이므로 {@code nodes}가 null이면 조용히 지나간다.
     */
    public static void applyDefaultPositions(List<NodeDto> nodes) {
        if (nodes == null) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            NodeDto node = nodes.get(i);
            double defaultX = DEFAULT_X_ORIGIN + i * DEFAULT_X_GAP;
            if (node.position == null) {
                node.position = new Position(defaultX, DEFAULT_Y);
            } else {
                Position position = node.position;
                if (position.x == null || position.y == null) {
                    node.position = new Position(
                        position.x != null ? position.x : defaultX,
                        position.y != null ? position.y : DEFAULT_Y);
                }
            }
        }
    }
}
