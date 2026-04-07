package com.ieum.api.prompt.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TestPromptTemplateResponse {

    private final String response;
    private final TokenUsageDto usage;
    private final long durationMs;
    private final RenderedPromptDto renderedPrompt;

    public record TokenUsageDto(int inputTokens, int outputTokens) {}

    public record RenderedPromptDto(String systemMessage, String userMessage) {}
}
