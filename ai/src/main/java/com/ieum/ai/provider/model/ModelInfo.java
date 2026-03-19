package com.ieum.ai.provider.model;

import java.util.List;

public record ModelInfo(
        String id,
        String displayName,
        List<String> capabilities,
        int maxOutputTokens,
        int contextWindow
) {}
