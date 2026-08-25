package com.ieum.api.workflow.dto;

import com.ieum.workflowcore.domain.enums.TriggerType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;

@Getter
public class CreateWorkflowRequest {

    @NotBlank
    private String name;

    private String description;

    /**
     * 원소의 {@code @NotNull}이 필요하다 — 필드에만 붙이면 리스트 자체만 검증해
     * {@code "nodes":[null]}이 통과한다(IEUM-BE-65).
     */
    @NotNull
    @Valid
    private List<@NotNull NodeDto> nodes;

    @NotNull
    @Valid
    private List<EdgeDto> edges;

    /** 트리거 타입. 미입력 시 MANUAL로 처리 */
    private TriggerType triggerType;

    /** SCHEDULE 트리거일 때 필수. Quartz 6자리 Cron 표현식 (예: "0 0 10 * * ?") */
    private String cronExpression;
}
