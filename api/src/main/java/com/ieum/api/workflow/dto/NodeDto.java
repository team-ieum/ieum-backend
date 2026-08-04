package com.ieum.api.workflow.dto;

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

    @NotNull(message = "노드 type은 필수입니다.")
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
     */
    public static void applyDefaultPositions(List<NodeDto> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            NodeDto node = nodes.get(i);
            if (node.position == null) {
                node.position = new Position(DEFAULT_X_ORIGIN + i * DEFAULT_X_GAP, DEFAULT_Y);
            }
        }
    }
}
