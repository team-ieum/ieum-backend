package com.ieum.api.workflow.dto;

import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.Getter;

@Getter
public class ApproveGateRequest {

    /** 선택. 거절 문구와 같은 상한 — {@code node_runs.error_message}가 VARCHAR(255)다. */
    @Size(max = 200, message = "메모는 200자 이하여야 합니다.")
    private String note;

    /** 선택. editable 게이트에서 고친 검토 내용 — 키는 items key, 값은 문자열(서비스가 검사). */
    private Map<String, Object> content;
}
