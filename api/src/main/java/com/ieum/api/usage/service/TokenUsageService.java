package com.ieum.api.usage.service;

import com.ieum.api.credential.domain.AiProvider;
import com.ieum.api.provider.model.ModelInfo;
import com.ieum.api.provider.model.ProviderInfo;
import com.ieum.api.provider.service.ProviderRegistry;
import com.ieum.api.usage.dto.TokenUsageResponse;
import com.ieum.api.usage.dto.TokenUsageResponse.DailyUsage;
import com.ieum.api.usage.dto.TokenUsageResponse.ModelUsage;
import com.ieum.api.usage.dto.TokenUsageResponse.ProviderUsage;
import com.ieum.api.usage.repository.AiTokenUsageQueryRepository;
import com.ieum.api.usage.repository.AiTokenUsageQueryRepository.UsageSum;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 토큰 사용량 조회(IEUM-BE-98). 모델 목록은 하드코딩하지 않고 {@link ProviderRegistry} 카탈로그를
 * 그대로 읽는다 — 카탈로그 모델을 0으로 채운 뒤 집계값을 덮어쓰고, 카탈로그 밖 실사용 모델(폐기 강등·
 * 카탈로그에서 빠진 모델)은 {@code inCatalog=false}로 뒤에 붙인다. provider 합계는 모델 합의 합이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TokenUsageService {

    private final AiTokenUsageQueryRepository queryRepository;
    private final ProviderRegistry providerRegistry;

    /**
     * @param from 시작일(포함). null이면 이번 달 1일
     * @param to   종료일(포함). null이면 오늘
     * @throws CustomException INVALID_INPUT — from이 to보다 늦거나 기간이 1년을 넘을 때
     *                         (from + 1년 당일부터 초과로 본다: 10-01 ~ 다음 해 09-30까지 허용)
     */
    public TokenUsageResponse getTokenUsage(UUID userId, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        LocalDate start = from != null ? from : today.withDayOfMonth(1);
        LocalDate end = to != null ? to : today;
        if (start.isAfter(end) || !end.isBefore(start.plusYears(1))) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }

        var fromInclusive = start.atStartOfDay();
        var toExclusive = end.plusDays(1).atStartOfDay();
        List<UsageSum> totals = queryRepository.sumByProviderAndModel(userId, fromInclusive, toExclusive);
        List<DailyUsage> daily = queryRepository.sumByDateProviderAndModel(userId, fromInclusive, toExclusive)
            .stream()
            .map(s -> new DailyUsage(s.date(), s.provider().name(), s.model(),
                s.inputTokens(), s.outputTokens(), s.totalTokens(), s.callCount()))
            .toList();

        Map<AiProvider, List<UsageSum>> byProvider =
            totals.stream().collect(Collectors.groupingBy(UsageSum::provider));
        List<ProviderUsage> providers = providerRegistry.getAllProviders().stream()
            .map(p -> toProviderUsage(p, byProvider.getOrDefault(AiProvider.valueOf(p.provider()), List.of())))
            .toList();

        return new TokenUsageResponse(start, end, providers, daily);
    }

    private static ProviderUsage toProviderUsage(ProviderInfo provider, List<UsageSum> sums) {
        Map<String, UsageSum> byModel = sums.stream().collect(Collectors.toMap(UsageSum::model, s -> s));
        List<ModelUsage> models = new ArrayList<>();
        for (ModelInfo m : provider.models()) {
            models.add(toModelUsage(m.id(), m.displayName(), m.isDefault(), true, byModel.remove(m.id())));
        }
        // 카탈로그 밖 모델 — 응답이 매번 같은 순서가 되도록 모델 ID 순
        byModel.values().stream()
            .sorted(Comparator.comparing(UsageSum::model))
            .forEach(s -> models.add(toModelUsage(s.model(), s.model(), false, false, s)));

        return new ProviderUsage(provider.provider(), provider.displayName(),
            models.stream().mapToLong(ModelUsage::inputTokens).sum(),
            models.stream().mapToLong(ModelUsage::outputTokens).sum(),
            models.stream().mapToLong(ModelUsage::totalTokens).sum(),
            models.stream().mapToLong(ModelUsage::callCount).sum(),
            models);
    }

    private static ModelUsage toModelUsage(String id, String displayName, boolean isDefault, boolean inCatalog,
                                           UsageSum sum) {
        return sum == null
            ? new ModelUsage(id, displayName, isDefault, inCatalog, 0, 0, 0, 0)
            : new ModelUsage(id, displayName, isDefault, inCatalog,
                sum.inputTokens(), sum.outputTokens(), sum.totalTokens(), sum.callCount());
    }
}
