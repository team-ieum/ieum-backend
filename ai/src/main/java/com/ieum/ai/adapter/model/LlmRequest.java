package com.ieum.ai.adapter.model;

import java.util.List;

public record LlmRequest(
    String model,
    String systemMessage,
    List<Message> messages,
    List<ToolDefinition> tools,
    ModelParameters parameters,
    String decryptedApiKey
) {}
