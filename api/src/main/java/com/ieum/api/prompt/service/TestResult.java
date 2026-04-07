package com.ieum.api.prompt.service;

import com.ieum.ai.adapter.model.TokenUsage;

public record TestResult(
    String response,
    TokenUsage usage,
    long durationMs,
    RenderedPrompt renderedPrompt
) {}
