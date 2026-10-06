package com.ieum.api.integration.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.ieum.api.credential.domain.AiProvider;
import com.ieum.api.credential.domain.Credential;
import com.ieum.api.credential.domain.CredentialType;
import com.ieum.api.credential.repository.CredentialQueryRepository;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.api.provider.model.ModelInfo;
import com.ieum.api.provider.service.ProviderRegistry;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** AI 모델·LLM 키 공급원의 입력 파싱·사용자 범위·항목 모양을 고정한다 (IEUM-BE-76). */
class LlmOptionSourcesTest {

    private final ProviderRegistry registry = new ProviderRegistry();
    private final AiModelsOptionSource models = new AiModelsOptionSource(registry);
    private final CredentialQueryRepository credentialQueryRepository = mock(CredentialQueryRepository.class);
    private final LlmCredentialsOptionSource credentials = new LlmCredentialsOptionSource(credentialQueryRepository);
    private final UUID userId = UUID.randomUUID();

    private List<ModelInfo> catalogModels(String provider) {
        return registry.getAllProviders().stream()
            .filter(p -> p.provider().equals(provider))
            .flatMap(p -> p.models().stream())
            .toList();
    }

    private static Credential credential(UUID owner, AiProvider provider, String displayName) {
        return Credential.builder()
            .id(UUID.randomUUID())
            .userId(owner)
            .provider(provider)
            .credentialType(CredentialType.API_KEY)
            .displayName(displayName)
            .encryptedApiKey("enc")
            .keyHint("sk-...ab12")
            .isValid(true)
            .build();
    }

    private static void assertInvalidInput(Runnable call) {
        assertThatThrownBy(call::run)
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("키 철자 — agent 카탈로그 optionsSource와 1:1")
    void keys() {
        assertThat(models.key()).isEqualTo("ai.models");
        assertThat(credentials.key()).isEqualTo("ieum.credentials");
    }

    @Test
    @DisplayName("ai.models — 해당 provider의 카탈로그 모델을 순서대로 {id, displayName}, 페이지 없음")
    void modelsOfProvider() {
        OptionPage page = models.fetch(userId, Map.of("llmProvider", "CLAUDE"), null);

        List<ModelInfo> expected = catalogModels("CLAUDE");
        assertThat(expected).isNotEmpty();
        assertThat(page.items()).extracting(OptionItem::id)
            .containsExactlyElementsOf(expected.stream().map(ModelInfo::id).toList());
        assertThat(page.items()).extracting(OptionItem::name)
            .containsExactlyElementsOf(expected.stream().map(ModelInfo::displayName).toList());
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("ai.models — 사용자 데이터가 아니라 userId와 무관하게 같은 목록")
    void modelsAreUserAgnostic() {
        OptionPage mine = models.fetch(userId, Map.of("llmProvider", "OPENAI"), null);
        OptionPage theirs = models.fetch(UUID.randomUUID(), Map.of("llmProvider", "OPENAI"), null);

        assertThat(mine.items()).isEqualTo(theirs.items());
    }

    @Test
    @DisplayName("llmProvider 앞뒤 공백·소문자는 다듬어 읽는다")
    void providerIsTrimmedAndUpperCased() {
        OptionPage page = models.fetch(userId, Map.of("llmProvider", " gemini "), null);

        assertThat(page.items()).extracting(OptionItem::id)
            .containsExactlyElementsOf(catalogModels("GEMINI").stream().map(ModelInfo::id).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"<missing>", "", "  ", "MISTRAL"})
    @DisplayName("llmProvider가 없거나 틀리면 두 공급원 모두 400 INVALID_INPUT, 저장소 조회 없음")
    void missingOrUnknownProviderIs400BeforeQuery(String raw) {
        Map<String, String> inputs = new HashMap<>();
        if (!"<missing>".equals(raw)) {
            inputs.put("llmProvider", raw);
        }

        assertInvalidInput(() -> models.fetch(userId, inputs, null));
        assertInvalidInput(() -> credentials.fetch(userId, inputs, null));
        verifyNoInteractions(credentialQueryRepository);
    }

    @Test
    @DisplayName("ieum.credentials — 요청자·provider로 거른 조회 하나만, {UUID, 표시 이름}")
    void credentialsOnlyCallerScoped() {
        Credential mine = credential(userId, AiProvider.OPENAI, "내 OpenAI 키");
        UUID otherUser = UUID.randomUUID();
        Credential theirs = credential(otherUser, AiProvider.OPENAI, "남의 키");
        given(credentialQueryRepository.findByUserIdAndProvider(userId, AiProvider.OPENAI)).willReturn(List.of(mine));
        given(credentialQueryRepository.findByUserIdAndProvider(otherUser, AiProvider.OPENAI)).willReturn(List.of(theirs));

        OptionPage page = credentials.fetch(userId, Map.of("llmProvider", "OPENAI"), null);

        assertThat(page.items()).containsExactly(new OptionItem(mine.getId().toString(), "내 OpenAI 키"));
        assertThat(page.nextCursor()).isNull();
        verify(credentialQueryRepository).findByUserIdAndProvider(userId, AiProvider.OPENAI);
        verifyNoMoreInteractions(credentialQueryRepository);
    }
}
