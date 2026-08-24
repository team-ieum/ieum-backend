package com.ieum.api.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.beta.config.BetaPlatformKeyProperties;
import com.ieum.api.provider.model.ModelInfo;
import com.ieum.api.provider.model.ProviderInfo;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProviderRegistryTest {

    private final ProviderRegistry registry = new ProviderRegistry();

    /**
     * ieum-agent core/config.py의 *_DEFAULT_MODEL 복제. agent 기본값이 바뀌면 여기도 바꾼다 —
     * 크로스레포라 코드로 묶을 수 없어 테스트로 고정한다.
     */
    private static final Map<String, String> AGENT_DEFAULTS = Map.of(
            "CLAUDE", "claude-sonnet-5",
            "OPENAI", "gpt-5.6-terra",
            "GEMINI", "gemini-3.7-flash"
    );

    /**
     * agent resolve_model이 강제 승격(_GEMINI_PROMOTED)하거나 litellm 폐기일이 지난 id,
     * 그리고 agent 표기와 다른 구 id. 등재하면 노드가 다른 모델로 실행되거나 채팅 편집마다 기본값으로 스냅백된다.
     */
    private static final Set<String> FORBIDDEN_IDS = Set.of(
            "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-3.5-flash",
            "claude-sonnet-4-20250514", "claude-haiku-4-5-20251001"
    );

    @Test
    @DisplayName("provider당 default 모델이 정확히 1개이고 agent 기본값과 같다")
    void exactlyOneDefaultPerProviderMatchingAgent() {
        for (ProviderInfo p : registry.getAllProviders()) {
            List<ModelInfo> defaults = p.models().stream().filter(ModelInfo::isDefault).toList();
            assertThat(defaults).as(p.provider()).hasSize(1);
            assertThat(defaults.get(0).id()).isEqualTo(AGENT_DEFAULTS.get(p.provider()));
        }
    }

    @Test
    @DisplayName("모든 모델 id는 bare id — 라우팅 접두가 없다")
    void idsAreBare() {
        registry.getAllProviders().stream()
                .flatMap(p -> p.models().stream())
                .forEach(m -> assertThat(m.id()).as(m.id()).doesNotContain("/"));
    }

    @Test
    @DisplayName("승격·폐기·구 표기 id는 카탈로그에 없다")
    void forbiddenIdsAbsent() {
        List<String> ids = registry.getAllProviders().stream()
                .flatMap(p -> p.models().stream()).map(ModelInfo::id).toList();
        assertThat(ids).doesNotContainAnyElementsOf(FORBIDDEN_IDS);
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("베타 플랫폼 허용 모델은 GEMINI 카탈로그에 있다")
    void betaAllowedModelsAreInGeminiCatalog() {
        List<String> geminiIds = registry.getAllProviders().stream()
                .filter(p -> p.provider().equals("GEMINI"))
                .flatMap(p -> p.models().stream()).map(ModelInfo::id).toList();
        assertThat(geminiIds).containsAll(new BetaPlatformKeyProperties().getAllowedModels());
    }

    @Test
    @DisplayName("JSON 필드명은 FE 계약대로 default — isDefault가 새지 않는다")
    void serializesDefaultKeyOnly() throws Exception {
        String json = new ObjectMapper().writeValueAsString(registry.getAllProviders());
        assertThat(json).contains("\"default\":true").doesNotContain("isDefault");
    }
}
