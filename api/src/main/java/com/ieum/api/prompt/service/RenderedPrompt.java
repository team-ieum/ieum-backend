package com.ieum.api.prompt.service;

public record RenderedPrompt(
    String systemMessage,
    String userMessage
) {}
