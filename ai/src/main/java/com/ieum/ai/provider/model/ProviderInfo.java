package com.ieum.ai.provider.model;

import java.util.List;

public record ProviderInfo(
        String provider,
        String displayName,
        List<String> credentialTypes,
        List<ModelInfo> models
) {}
