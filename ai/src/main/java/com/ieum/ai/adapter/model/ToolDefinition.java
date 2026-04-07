package com.ieum.ai.adapter.model;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolDefinition(
    String name,
    String description,
    JsonNode inputSchema
) {}
