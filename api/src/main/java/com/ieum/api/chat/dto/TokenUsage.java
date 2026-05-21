package com.ieum.api.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** AI 응답의 토큰 사용량. */
@Getter
@AllArgsConstructor
public class TokenUsage {

    private final int inputTokens;
    private final int outputTokens;
}
