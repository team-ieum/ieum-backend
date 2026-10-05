package com.ieum.api.integration.options;

import com.ieum.api.credential.domain.AiProvider;
import com.ieum.api.credential.repository.CredentialQueryRepository;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * AI 노드 {@code credentialId} 필드의 선택지 — 요청자의 LLM 키 중 고른 provider 것만.
 * 조회는 userId로 거른 하나뿐이다(IEUM-BE-64) — 남의 크레덴셜은 저장 시 {@code NodeCredentialGuard}도 막는다.
 * 키 원문·힌트는 싣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class LlmCredentialsOptionSource implements OptionSource {

    private final CredentialQueryRepository credentialQueryRepository;

    @Override
    public String key() {
        return "ieum.credentials";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        AiProvider provider = AiModelsOptionSource.requireProvider(inputs);
        List<OptionItem> items = credentialQueryRepository.findByUserIdAndProvider(userId, provider).stream()
            .map(c -> new OptionItem(c.getId().toString(), c.getDisplayName()))
            .toList();
        return new OptionPage(items, null);
    }
}
