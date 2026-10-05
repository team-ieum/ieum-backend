package com.ieum.api.integration.options;

import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.api.webhookcredential.domain.WebhookCredential;
import com.ieum.api.webhookcredential.domain.WebhookProvider;
import com.ieum.api.webhookcredential.repository.WebhookCredentialRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/**
 * 웹훅 크레덴셜 선택지. {@code provider}가 null이면 전체(HTTP 노드 — 이름 뒤에 서비스를 붙인다),
 * 아니면 그 서비스만(Slack·Discord 액션). 키를 서비스별로 나눈 이유: 카탈로그 {@code optionsInputs}는
 * 같은 노드의 필드만 가리켜 "서비스" 같은 고정 조건을 넘길 수 없다. 빈은 {@link WebhookOptionSourceConfig}가 만든다.
 *
 * <p>기준은 채팅 가용 목록({@code ChatService.resolveAvailableWebhooks})과 같다 — 요청자 것 중 활성만.
 * 실행({@code DefaultWebhookCredentialProvider})이 비활성을 건너뛰므로 보여 주면 조용히 실패한다. URL은 싣지 않는다.
 */
@RequiredArgsConstructor
public class WebhookOptionSource implements OptionSource {

    private final String key;
    private final WebhookProvider provider;
    private final WebhookCredentialRepository repository;

    @Override
    public String key() {
        return key;
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        List<OptionItem> items = repository.findByUserId(userId).stream()
            .filter(WebhookCredential::isEnabled)
            .filter(w -> provider == null || w.getProvider() == provider)
            .map(w -> new OptionItem(w.getId().toString(), provider == null
                ? w.getDisplayName() + " (" + w.getProvider().name() + ")"
                : w.getDisplayName()))
            .toList();
        return new OptionPage(items, null);
    }
}
