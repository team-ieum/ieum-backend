package com.ieum.api.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class EdgeDto {

    @NotBlank(message = "엣지 source는 필수입니다.")
    private String source;

    @NotBlank(message = "엣지 target은 필수입니다.")
    private String target;

    /** CONDITION 노드의 분기 값("true"/"false"). 일반 연결에서는 비어 있다. */
    private String conditionType;
}
