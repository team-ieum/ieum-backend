package com.ieum.api.workflow.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class RejectExecutionRequest {

    /**
     * 선택. 게이트 노드 로그의 {@code error_message}(VARCHAR 255)에 "승인 거부: " 접두어와 함께 들어간다 —
     * 넘치면 저장이 500으로 깨지므로 요청 경계에서 막는다.
     */
    @Size(max = 200, message = "거부 사유는 200자 이하여야 합니다.")
    private String reason;
}
