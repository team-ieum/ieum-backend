package com.ieum.api.integration.options;

import com.ieum.api.credential.domain.AiProvider;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.api.provider.service.ProviderRegistry;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * AI 노드 {@code model} 필드의 선택지 — provider별 실행 모델. 원본은 providers API와 같은 {@link ProviderRegistry}다
 * (두 번째 목록을 만들면 한쪽만 고쳐진다). 사용자 데이터가 아니라 userId는 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AiModelsOptionSource implements OptionSource {

    private final ProviderRegistry providerRegistry;

    @Override
    public String key() {
        return "ai.models";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        String provider = requireProvider(inputs).name();
        List<OptionItem> items = providerRegistry.getAllProviders().stream()
            .filter(p -> p.provider().equals(provider))
            .flatMap(p -> p.models().stream())
            .map(m -> new OptionItem(m.id(), m.displayName()))
            .toList();
        return new OptionPage(items, null);
    }

    /** {@code llmProvider} 입력을 읽는다. LLM 키 공급원도 같은 규칙을 쓴다 — 저장소 조회 전에 부른다. */
    static AiProvider requireProvider(Map<String, String> inputs) {
        String raw = inputs.get("llmProvider");
        try {
            return AiProvider.valueOf(raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "llmProvider가 없거나 올바르지 않습니다.");
        }
    }
}
