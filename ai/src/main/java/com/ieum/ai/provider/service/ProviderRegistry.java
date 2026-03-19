package com.ieum.ai.provider.service;

import com.ieum.ai.provider.model.ModelInfo;
import com.ieum.ai.provider.model.ProviderInfo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProviderRegistry {

    public List<ProviderInfo> getAllProviders() {
        return List.of(claude(), openai(), gemini());
    }

    private ProviderInfo claude() {
        return new ProviderInfo(
                "CLAUDE",
                "Anthropic Claude",
                List.of("API_KEY"),
                List.of(
                        new ModelInfo("claude-sonnet-4-20250514", "Claude Sonnet 4",
                                List.of("text", "tools", "vision"), 8192, 200000),
                        new ModelInfo("claude-haiku-4-5-20251001", "Claude Haiku 4.5",
                                List.of("text", "tools"), 4096, 200000)
                )
        );
    }

    private ProviderInfo openai() {
        return new ProviderInfo(
                "OPENAI",
                "OpenAI",
                List.of("API_KEY"),
                List.of(
                        new ModelInfo("gpt-4o", "GPT-4o",
                                List.of("text", "tools", "vision"), 4096, 128000),
                        new ModelInfo("gpt-4o-mini", "GPT-4o Mini",
                                List.of("text", "tools"), 4096, 128000)
                )
        );
    }

    private ProviderInfo gemini() {
        return new ProviderInfo(
                "GEMINI",
                "Google Gemini",
                List.of("API_KEY", "OAUTH"),
                List.of(
                        new ModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash",
                                List.of("text", "tools", "vision"), 8192, 1000000)
                )
        );
    }
}
