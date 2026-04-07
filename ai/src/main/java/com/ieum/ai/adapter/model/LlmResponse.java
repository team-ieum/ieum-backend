package com.ieum.ai.adapter.model;

import java.util.List;

public record LlmResponse(
    String id,
    StopReason stopReason,
    String textContent,
    List<ToolUseRequest> toolUses,
    TokenUsage usage
) {}
