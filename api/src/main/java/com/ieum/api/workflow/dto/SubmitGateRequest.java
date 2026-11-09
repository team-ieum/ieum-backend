package com.ieum.api.workflow.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.Getter;

@Getter
public class SubmitGateRequest {

    /** 칸 key → 값(문자열·숫자·불리언). 칸별 검사·변환은 서비스가 한다. */
    @NotNull(message = "values는 필수입니다.")
    private Map<String, Object> values;

    @Size(max = 200, message = "메모는 200자 이하여야 합니다.")
    private String note;
}
