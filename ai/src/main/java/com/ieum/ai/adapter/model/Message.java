package com.ieum.ai.adapter.model;

import com.fasterxml.jackson.databind.JsonNode;

public record Message(
    Role role,
    String content,
    String toolUseId,
    String toolName,
    JsonNode toolInput
) {
    public static Message user(String content) {
        return new Message(Role.USER, content, null, null, null);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, null, null, null);
    }
}
