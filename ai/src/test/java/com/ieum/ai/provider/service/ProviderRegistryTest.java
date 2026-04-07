package com.ieum.ai.provider.service;

import com.ieum.ai.provider.model.ProviderInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ProviderRegistryTest {

    private final ProviderRegistry providerRegistry = new ProviderRegistry();

    @Test
    void getAllProviders_returns3Providers() {
        List<ProviderInfo> providers = providerRegistry.getAllProviders();
        assertThat(providers).hasSize(3);
    }

    @Test
    void getAllProviders_containsClaudeOpenAiGemini() {
        List<String> providerNames = providerRegistry.getAllProviders()
                .stream().map(ProviderInfo::provider).toList();

        assertThat(providerNames).containsExactly("CLAUDE", "OPENAI", "GEMINI");
    }

    @Test
    void claude_has2Models() {
        ProviderInfo claude = getProvider("CLAUDE");
        assertThat(claude.models()).hasSize(2);
        assertThat(claude.models().stream().map(m -> m.id()).toList())
                .containsExactly("claude-sonnet-4-20250514", "claude-haiku-4-5-20251001");
    }

    @Test
    void openai_has2Models() {
        ProviderInfo openai = getProvider("OPENAI");
        assertThat(openai.models()).hasSize(2);
    }

    @Test
    void gemini_supportsOauth() {
        ProviderInfo gemini = getProvider("GEMINI");
        assertThat(gemini.credentialTypes()).contains("OAUTH");
    }

    @Test
    void claude_supportsVision() {
        ProviderInfo claude = getProvider("CLAUDE");
        boolean hasVision = claude.models().stream()
                .anyMatch(m -> m.capabilities().contains("vision"));
        assertThat(hasVision).isTrue();
    }

    private ProviderInfo getProvider(String providerName) {
        return providerRegistry.getAllProviders().stream()
                .filter(p -> p.provider().equals(providerName))
                .findFirst()
                .orElseThrow();
    }
}
