package com.ieum.api.prompt.service;


public record TestResult(
    String response,
    TokenUsage usage,
    long durationMs,
    RenderedPrompt renderedPrompt
) {}
