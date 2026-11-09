package com.ieum.api.workflow.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class RejectGateRequest {

    /** 선택. 오류 문구 {@code "승인 거부: <note>"}가 {@code node_runs.error_message}(VARCHAR 255)에 들어간다. */
    @Size(max = 200, message = "메모는 200자 이하여야 합니다.")
    private String note;
}
