package com.ieum.ai.adapter.model;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolUseRequest(
    String id,
    String toolName,
    JsonNode input
) {}
