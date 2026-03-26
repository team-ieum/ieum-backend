package com.ieum.ai.adapter.model;

import java.util.List;

public record ModelParameters(
    Double temperature,
    Integer maxTokens,
    Double topP,
    List<String> stop
) {}
