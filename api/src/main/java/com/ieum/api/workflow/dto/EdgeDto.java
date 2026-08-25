package com.ieum.api.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class EdgeDto {

    /**
     * 엣지 식별자. <b>선택 필드다</b> — 필수로 두면 채팅 워크플로우가 저장·수정 불가가 된다
     * (IEUM-BE-65). ieum-agent가 만든 엣지에는 id가 없고, 그 정의를 읽어 되돌려 보내는
     * 클라이언트 요청에도 id가 없기 때문이다.
     *
     * <p>없거나 비어 있으면 서버가 저장 직전에 채운다({@code WorkflowService}). 값이 있으면 그대로
     * 저장하며 검증하지 않는다 — 노드 id가 클라이언트 소유인 것과 같은 선이다.
     */
    private String id;

    @NotBlank(message = "엣지 source는 필수입니다.")
    private String source;

    @NotBlank(message = "엣지 target은 필수입니다.")
    private String target;

    /** CONDITION 노드의 분기 값("true"/"false"). 일반 연결에서는 비어 있다. */
    private String conditionType;
}
