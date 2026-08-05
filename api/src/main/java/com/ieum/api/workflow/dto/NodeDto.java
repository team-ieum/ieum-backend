package com.ieum.api.workflow.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ieum.workflowcore.domain.enums.NodeType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 워크플로우 생성·수정 <b>요청</b>의 노드. 조회 응답은 {@link NodeView}가 담당한다.
 *
 * <p>아래 검증 어노테이션은 {@code @Valid}가 붙은 요청 본문에서만 강제된다 — 조회 경로는 이 DTO를
 * 거치지 않으므로 응답 쪽 불변식을 여기에 기대면 안 된다.
 */
@Getter
public class NodeDto {

    @NotBlank(message = "노드 id는 필수입니다.")
    private String id;

    /**
     * 알 수 없는 값은 역직렬화 단계에서 예외 대신 null로 흘리고, 그 null을 아래 {@code @NotNull}이
     * 400으로 잡는다. 예외로 두면 본문 전체 파싱 실패({@code HttpMessageNotReadableException})가 되어
     * 응답이 어느 필드가 잘못됐는지 알려주지 못한다(둘 다 400이라 상태 코드는 같다).
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
}
