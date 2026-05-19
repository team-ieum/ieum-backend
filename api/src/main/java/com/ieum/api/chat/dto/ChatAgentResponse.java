package com.ieum.api.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ieum-agent POST /v1/chat 응답 body (ChatResponse 형식).
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatAgentResponse {

    /** AI 응답 텍스트 */
    private String message;

    /** 응답 타입 (WORKFLOW_GENERATED, CLARIFICATION_NEEDED 등) */
    private String type;

    /** 편의 메서드 — message 필드를 반환한다 */
    public String getContent() {
        return message;
    }

    /** /v1/chat 은 토큰 정보를 반환하지 않으므로 null 반환 */
    public Integer getInputTokens() {
        return null;
    }

    public Integer getOutputTokens() {
        return null;
    }
}
