package com.ieum.api.prompt.service;

public record TokenUsage(int inputTokens, int outputTokens) {

    public static TokenUsage zero() {
        return new TokenUsage(0, 0);
    }

    public TokenUsage add(TokenUsage other) {
        return new TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }
}
