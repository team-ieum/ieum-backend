package com.ieum.api.chat.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ieum-agent POST /v1/chat 응답 body.
 */
@Getter
@NoArgsConstructor
public class ChatAgentResponse {

    private boolean success;
    private String content;
    private Integer inputTokens;
    private Integer outputTokens;
    private String errorMessage;
}
