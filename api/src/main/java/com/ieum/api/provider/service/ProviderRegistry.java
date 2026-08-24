package com.ieum.api.provider.service;

import com.ieum.api.provider.model.ModelInfo;
import com.ieum.api.provider.model.ProviderInfo;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * provider별 모델 카탈로그. FE 노드 패널 셀렉트의 유일한 출처.
 *
 * <p>등재 규칙(IEUM-BE-67, ProviderRegistryTest가 고정):
 * bare id만 · provider당 default 1개 = agent 기본값 · litellm 폐기 모델과 agent가 강제 승격하는
 * gemini-2.5-flash 계열은 금지. 토큰 한도는 litellm model_cost 실측값(2026-08-24).
 * 저장 시 model ∈ 카탈로그 검증은 하지 않는다 — 폐기 강등은 agent resolve_model이 맡는다.
 */
@Service
public class ProviderRegistry {

    private static final List<String> ALL = List.of("text", "tools", "vision");

    public List<ProviderInfo> getAllProviders() {
        return List.of(claude(), openai(), gemini());
    }

    private ProviderInfo claude() {
        return new ProviderInfo(
                "CLAUDE",
                "Anthropic Claude",
                List.of("API_KEY"),
                List.of(
                        // litellm 폐기일 2026-10-15 — 후계 모델 등재 시 교체
                        new ModelInfo("claude-haiku-4-5", "Claude Haiku 4.5", ALL, 64000, 200000, false),
                        new ModelInfo("claude-sonnet-5", "Claude Sonnet 5", ALL, 128000, 1000000, true),
                        new ModelInfo("claude-opus-5", "Claude Opus 5", ALL, 128000, 1000000, false)
                )
        );
    }

    private ProviderInfo openai() {
        return new ProviderInfo(
                "OPENAI",
                "OpenAI",
                List.of("API_KEY"),
                List.of(
                        new ModelInfo("gpt-5.6-luna", "GPT-5.6 Luna", ALL, 128000, 922000, false),
                        new ModelInfo("gpt-5.6-terra", "GPT-5.6 Terra", ALL, 128000, 922000, true),
                        new ModelInfo("gpt-5.6-sol", "GPT-5.6 Sol", ALL, 128000, 922000, false)
                )
        );
    }

    private ProviderInfo gemini() {
        return new ProviderInfo(
                "GEMINI",
                "Google Gemini",
                List.of("API_KEY", "OAUTH"),
                List.of(
                        new ModelInfo("gemini-3.5-flash-lite", "Gemini 3.5 Flash Lite", ALL, 65536, 1048576, false),
                        new ModelInfo("gemini-3.7-flash", "Gemini 3.7 Flash", ALL, 65536, 1048576, true),
                        new ModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro", ALL, 65535, 1048576, false)
                )
        );
    }
}
