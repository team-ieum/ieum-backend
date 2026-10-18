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

    /**
     * 검증용(선택). 서버가 노드에서 계산한 트리거 타입과 다르면 400 {@code INVALID_WORKFLOW}, 미입력이면 통과한다.
     * 저장되는 값은 늘 계산값이다 — 첫 번째 TRIGGER 노드의 event, 트리거 노드가 없으면 MANUAL (IEUM-BE-92).
     */
    private TriggerType triggerType;

    /**
     * 검증용(선택). 계산된 트리거가 SCHEDULE일 때만 보낼 수 있고, 노드 {@code config.cron}과 Quartz로 정규화해 같아야 한다.
     * 미입력(빈 문자열 포함)이면 통과한다 (IEUM-BE-92).
     */
    private String cronExpression;
}
