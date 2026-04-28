package com.ieum.api.credential.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AiProvider {

    CLAUDE("Anthropic Claude"),
    OPENAI("OpenAI"),
    GEMINI("Google Gemini");

    private final String displayName;
}
