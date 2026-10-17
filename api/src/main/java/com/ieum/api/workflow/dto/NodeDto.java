package com.ieum.api.workflow.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ieum.workflowcore.domain.enums.NodeType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
     * 선택 필드다. 보내도 무시하고 서버가 {@code event}로 계산한 값으로 덮는다(IEUM-BE-92). 알 수 없는 값은
     * 예외 대신 null로 흘린다. 본문 전체 파싱이 실패하면 응답이 어느 필드가 잘못됐는지 알려주지 못하기 때문이다.
     */
    @JsonFormat(with = JsonFormat.Feature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
    private NodeType type;

    @NotBlank(message = "노드 label은 필수입니다.")
    private String label;

    /**
     * 일반 사용자에게 보여줄 자연어 설명. 개발자용 기술 설명이 아니다.
     *
     * <p>권장하되 <b>선택</b> 필드다 — 프론트에 이 값을 채워 보내는 경로가 없어, 필수로 두면 실제
     * 클라이언트의 워크플로우 생성·수정이 전부 400으로 막힌다(IEUM-BE-60).
     */
    private String description;

    /**
     * 캔버스 위치. 좌표를 확정하는 주체는 프론트고, 서버는 받은 값을 그대로 저장한다.
     *
     * <p>{@code description}과 같은 이유로 <b>선택</b> 필드다 — 프론트의 노드 요청 타입에 이 필드가
     * 없어, 필수로 두면 저장이 전부 400으로 막힌다(IEUM-BE-60). 다만 <b>보냈다면 완전해야 한다</b> —
     * {@code @Valid}가 살아 있어 {@code x}나 {@code y}가 빠진 반쪽 좌표는 여전히 400이다.
     */
    @Valid
    private Position position;

    /** 노드 종류별 실행 설정 (프롬프트, URL, 조건식 등). */
    @Schema(
        description = "노드 종류별 실행 설정. 값에는 문자열·숫자·boolean·배열·객체가 모두 올 수 있다"
            + " (예: HTTP의 method/url, CONDITION의 operator).",
        additionalProperties = Schema.AdditionalPropertiesValue.TRUE,
        example = "{\"method\": \"POST\", \"url\": \"https://example.com/hook\", \"headers\": {}}")
    private Map<String, Object> config;

    /**
     * 노드 종류. 카탈로그 이벤트 이름이다(예: {@code github.create_issue}). 노드의 유일한 정체성이다(IEUM-BE-92).
     * 카탈로그에 없는 값은 저장 단계({@code NodeDerivation})에서 400이 된다.
     */
    @NotBlank(message = "노드 event는 필수입니다.")
    @Size(max = 100, message = "노드 event는 100자 이하여야 합니다.")
    @Schema(description = "카탈로그 이벤트 이름. type은 서버가 이 값으로 계산한다.", example = "github.create_issue")
    private String event;

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
