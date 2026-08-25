package com.ieum.api.workflow.dto;

import com.ieum.workflowcore.domain.enums.TriggerType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;

@Getter
public class UpdateWorkflowRequest {

    @NotBlank
    private String name;

    /**
     * 미입력({@code null} 포함) 시 저장된 값을 유지한다(부분 갱신 — IEUM-BE-65).
     *
     * <p>그래서 설명을 <b>비우려면</b> 빈 문자열을 보내야 한다. 노드 필드와 규칙을 맞춘 의도된
     * 트레이드오프다 — "null은 변경 없음"과 "null은 삭제"를 필드마다 다르게 두지 않는다.
     */
    private String description;

    /**
     * 원소의 {@code @NotNull}이 필요하다 — 필드에만 붙이면 리스트 자체만 검증해
     * {@code "nodes":[null]}이 통과한다(IEUM-BE-65).
     */
    @NotNull
    @Valid
    private List<@NotNull NodeDto> nodes;

    /** {@code nodes}와 같은 이유로 원소에도 {@code @NotNull}이 필요하다 — {@code "edges":[null]}이 저장되면
     * 실행 시 엣지를 훑는 자리에서 NPE가 난다(IEUM-BE-65). */
    @NotNull
    @Valid
    private List<@NotNull EdgeDto> edges;

    /** 트리거 타입. 미입력 시 저장된 값을 유지한다(부분 갱신 — IEUM-BE-65). */
    private TriggerType triggerType;

    /**
     * SCHEDULE 트리거일 때 필수. Quartz 6자리 Cron 표현식 (예: "0 0 10 * * ?")
     *
     * <p>미입력 시 유효 트리거가 SCHEDULE이면 저장된 값을 유지하고, 아니면 비운다(IEUM-BE-65).
     */
    private String cronExpression;
}
