package com.ieum.api.usage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.api.credential.domain.AiProvider;
import com.ieum.api.provider.model.ModelInfo;
import com.ieum.api.provider.model.ProviderInfo;
import com.ieum.api.provider.service.ProviderRegistry;
import com.ieum.api.usage.domain.AiTokenUsage;
import com.ieum.api.usage.domain.AiTokenUsage.KeySource;
import com.ieum.api.usage.domain.AiTokenUsage.UsageSource;
import com.ieum.api.usage.dto.TokenUsageResponse;
import com.ieum.api.usage.dto.TokenUsageResponse.ModelUsage;
import com.ieum.api.usage.dto.TokenUsageResponse.ProviderUsage;
import com.ieum.api.usage.repository.AiTokenUsageRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** 실제 H2 스키마에서 집계·0 채움·카탈로그 밖 모델·기간 필터를 본다. 모델 ID는 카탈로그에서 읽는다. */
@SpringBootTest
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class TokenUsageServiceTest {

    @Autowired private TokenUsageService service;
    @Autowired private AiTokenUsageRepository repository;
    @Autowired private ProviderRegistry providerRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final UUID userId = UUID.randomUUID();
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    private void usage(UUID owner, AiProvider provider, String model, LocalDateTime at,
                       Integer in, Integer out, Integer total) {
        AiTokenUsage saved = repository.save(AiTokenUsage.builder()
            .userId(owner).provider(provider).model(model)
            .keySource(KeySource.BYOK).source(UsageSource.WORKFLOW)
            .workflowId(UUID.randomUUID())   // FK 없음 — 워크플로우가 지워져도 행은 그대로다
            .inputTokens(in).outputTokens(out).totalTokens(total)
            .build());
        jdbcTemplate.update("UPDATE ai_token_usage SET created_at = ? WHERE id = ?", at, saved.getId());
    }

    private ProviderInfo catalog(AiProvider provider) {
        return providerRegistry.getAllProviders().stream()
            .filter(p -> p.provider().equals(provider.name())).findFirst().orElseThrow();
    }

    private String defaultModel(AiProvider provider) {
        return catalog(provider).models().stream().filter(ModelInfo::isDefault).findFirst().orElseThrow().id();
    }

    private ProviderUsage provider(TokenUsageResponse r, AiProvider provider) {
        return r.providers().stream().filter(p -> p.provider().equals(provider.name())).findFirst().orElseThrow();
    }

    private ModelUsage model(ProviderUsage p, String model) {
        return p.models().stream().filter(m -> m.model().equals(model)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("provider → 모델 합계 — 카탈로그 모델은 0 포함 카탈로그 순서, 카탈로그 밖 모델은 뒤에, provider 합 = 모델 합")
    void aggregatesByProviderAndModel() {
        String claudeDefault = defaultModel(AiProvider.CLAUDE);
        usage(userId, AiProvider.CLAUDE, claudeDefault, FROM.atTime(9, 0), 100, 30, 130);
        usage(userId, AiProvider.CLAUDE, claudeDefault, FROM.atTime(23, 59), 50, 10, 60);
        usage(userId, AiProvider.CLAUDE, claudeDefault, TO.atTime(12, 0), null, null, null);   // usage 미보고
        usage(userId, AiProvider.GEMINI, "gemini-legacy-x", TO.atTime(12, 0), 7, 3, 10);

        TokenUsageResponse r = service.getTokenUsage(userId, FROM, TO);

        assertThat(r.from()).isEqualTo(FROM);
        assertThat(r.to()).isEqualTo(TO);
        assertThat(r.providers()).extracting(ProviderUsage::provider)
            .containsExactlyElementsOf(providerRegistry.getAllProviders().stream().map(ProviderInfo::provider).toList());

        ProviderUsage claude = provider(r, AiProvider.CLAUDE);
        assertThat(claude.models()).extracting(ModelUsage::model)
            .containsExactlyElementsOf(catalog(AiProvider.CLAUDE).models().stream().map(ModelInfo::id).toList());
        ModelUsage used = model(claude, claudeDefault);
        assertThat(used.isDefault()).isTrue();
        assertThat(used.inCatalog()).isTrue();
        assertThat(used.inputTokens()).isEqualTo(150);
        assertThat(used.totalTokens()).isEqualTo(190);
        assertThat(used.callCount()).isEqualTo(3);
        assertThat(claude.totalTokens()).isEqualTo(190);
        assertThat(claude.callCount()).isEqualTo(3);

        ProviderUsage openai = provider(r, AiProvider.OPENAI);
        assertThat(openai.callCount()).isZero();
        assertThat(openai.models()).hasSize(catalog(AiProvider.OPENAI).models().size())
            .allSatisfy(m -> assertThat(m.callCount()).isZero());

        ProviderUsage gemini = provider(r, AiProvider.GEMINI);
        ModelUsage extra = gemini.models().get(gemini.models().size() - 1);
        assertThat(extra.model()).isEqualTo("gemini-legacy-x");
        assertThat(extra.inCatalog()).isFalse();
        assertThat(extra.displayName()).isEqualTo("gemini-legacy-x");
        assertThat(gemini.models()).hasSize(catalog(AiProvider.GEMINI).models().size() + 1);
        assertThat(gemini.totalTokens()).isEqualTo(10);
    }

    @Test
    @DisplayName("일별 — 날짜 × 모델, 사용량 있는 날·모델만, 날짜 오름차순")
    void dailyByDateAndModel() {
        String claudeDefault = defaultModel(AiProvider.CLAUDE);
        usage(userId, AiProvider.CLAUDE, claudeDefault, FROM.atTime(9, 0), 100, 30, 130);
        usage(userId, AiProvider.CLAUDE, claudeDefault, FROM.atTime(23, 59), 50, 10, 60);
        usage(userId, AiProvider.GEMINI, "gemini-legacy-x", TO.atTime(0, 0), 7, 3, 10);

        List<TokenUsageResponse.DailyUsage> daily = service.getTokenUsage(userId, FROM, TO).daily();

        assertThat(daily).hasSize(2);
        assertThat(daily.get(0).date()).isEqualTo(FROM);
        assertThat(daily.get(0).model()).isEqualTo(claudeDefault);
        assertThat(daily.get(0).totalTokens()).isEqualTo(190);
        assertThat(daily.get(0).callCount()).isEqualTo(2);
        assertThat(daily.get(1).date()).isEqualTo(TO);
        assertThat(daily.get(1).provider()).isEqualTo("GEMINI");
    }

    @Test
    @DisplayName("기간 밖·다른 사용자 데이터는 포함하지 않는다(경계: from 00:00 포함, to 다음날 00:00 제외)")
    void excludesOutOfRangeAndOtherUsers() {
        String claudeDefault = defaultModel(AiProvider.CLAUDE);
        usage(userId, AiProvider.CLAUDE, claudeDefault, FROM.atStartOfDay(), 1, 1, 2);
        usage(userId, AiProvider.CLAUDE, claudeDefault, FROM.minusDays(1).atTime(23, 59, 59), 1000, 0, 1000);
        usage(userId, AiProvider.CLAUDE, claudeDefault, TO.plusDays(1).atStartOfDay(), 1000, 0, 1000);
        usage(UUID.randomUUID(), AiProvider.CLAUDE, claudeDefault, FROM.atTime(12, 0), 1000, 0, 1000);

        TokenUsageResponse r = service.getTokenUsage(userId, FROM, TO);

        assertThat(provider(r, AiProvider.CLAUDE).totalTokens()).isEqualTo(2);
        assertThat(r.daily()).hasSize(1);
    }

    @Test
    @DisplayName("기간 생략 — 이번 달 1일 ~ 오늘")
    void defaultsToThisMonth() {
        TokenUsageResponse r = service.getTokenUsage(userId, null, null);

        LocalDate today = LocalDate.now();
        assertThat(r.from()).isEqualTo(today.withDayOfMonth(1));
        assertThat(r.to()).isEqualTo(today);
        assertThat(r.providers()).hasSize(3);
        assertThat(r.daily()).isEmpty();
    }

    @Test
    @DisplayName("from > to, 1년 초과는 INVALID_INPUT — 1년에서 하루 모자란 기간은 허용")
    void validatesRange() {
        assertThatThrownBy(() -> service.getTokenUsage(userId, TO, FROM))
            .isInstanceOf(CustomException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> service.getTokenUsage(userId, FROM, FROM.plusYears(1)))
            .isInstanceOf(CustomException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        assertThat(service.getTokenUsage(userId, FROM, FROM.plusYears(1).minusDays(1)).to())
            .isEqualTo(FROM.plusYears(1).minusDays(1));
    }
}
