package com.ieum.api.usage.service;

import com.ieum.api.credential.domain.AiProvider;
import com.ieum.api.provider.model.ModelInfo;
import com.ieum.api.provider.service.ProviderRegistry;
import com.ieum.api.usage.domain.AiTokenUsage;
import com.ieum.api.usage.domain.AiTokenUsage.KeySource;
import com.ieum.api.usage.domain.AiTokenUsage.UsageSource;
import com.ieum.api.usage.repository.AiTokenUsageRepository;
import java.util.UUID;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI 호출 1회를 {@code ai_token_usage}에 1행으로 남긴다.
 *
 * <p><b>기록은 호출자를 절대 막지 않는다.</b> 저장 실패는 경고 로그로 삼키고, 저장은 호출자 트랜잭션과
 * 분리된 {@code REQUIRES_NEW}로 한다 — 채팅처럼 {@code @Transactional} 안에서 불려도 기록 실패가
 * 바깥 트랜잭션을 rollback-only로 만들지 않고, 바깥이 롤백돼도 이미 쓴 토큰 기록은 남는다.
 *
 * <p><b>모델 결정</b> — agent는 아직 실제 호출 모델을 응답에 싣지 않는다. 그래서 다음 순서로 정한다.
 * <ol>
 *   <li>베타 platform 키: agent가 노드 model을 무시하고 Gemini 기본 모델로 강등한다 → GEMINI + 카탈로그 기본</li>
 *   <li>agent가 응답에 model을 실었으면 그 값(폐기 모델 강등까지 반영된 진실)</li>
 *   <li>요청한 모델(재시도 fallback 반영). 비었으면 agent가 provider 기본 모델을 쓰므로 카탈로그 기본</li>
 * </ol>
 * 어느 경우든 provider 접두사({@code anthropic/} 등)를 떼서 카탈로그와 같은 bare id로 맞춘다.
 */
@Slf4j
@Service
public class AiTokenUsageRecorder {

    private final AiTokenUsageRepository repository;
    private final ProviderRegistry providerRegistry;
    private final TransactionTemplate requiresNew;

    public AiTokenUsageRecorder(AiTokenUsageRepository repository, ProviderRegistry providerRegistry,
                                PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.providerRegistry = providerRegistry;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @param provider       요청한 provider 코드(노드·채팅 설정값). platform 키면 무시하고 GEMINI
     * @param requestedModel 요청한 모델(nullable)
     * @param reportedModel  agent가 응답에 실은 실제 모델(nullable — 현재 agent는 싣지 않는다)
     */
    @Builder
    public record UsageCall(
        UUID userId, String provider, String requestedModel, String reportedModel, boolean platformKey,
        UsageSource source, UUID workflowId, UUID executionId,
        Integer inputTokens, Integer outputTokens, Integer totalTokens
    ) {}

    public void record(UsageCall call) {
        try {
            AiTokenUsage usage = toEntity(call);
            if (usage == null) {
                return;
            }
            requiresNew.executeWithoutResult(status -> repository.save(usage));
        } catch (Exception e) {
            // 토큰 수·사용자 ID 외 값(키·프롬프트)은 남기지 않는다
            log.warn("[AiTokenUsageRecorder] 사용량 기록 실패 — userId: {}, source: {}",
                call.userId(), call.source(), e);
        }
    }

    private AiTokenUsage toEntity(UsageCall call) {
        AiProvider provider = call.platformKey() ? AiProvider.GEMINI : parseProvider(call.provider());
        if (call.userId() == null || provider == null) {
            log.warn("[AiTokenUsageRecorder] 사용자·provider를 알 수 없어 기록 생략 — userId: {}, provider: {}",
                call.userId(), call.provider());
            return null;
        }
        // platform 키면 요청 모델은 agent가 무시하므로 버린다(응답 모델은 여전히 우선)
        String model = firstNonBlank(call.reportedModel(), call.platformKey() ? null : call.requestedModel());
        model = model != null ? normalizeModelId(model) : defaultModel(provider);
        if (model == null) {
            log.warn("[AiTokenUsageRecorder] 모델을 정할 수 없어 기록 생략 — provider: {}", provider);
            return null;
        }
        return AiTokenUsage.builder()
            .userId(call.userId())
            .provider(provider)
            .model(model)
            .keySource(call.platformKey() ? KeySource.PLATFORM : KeySource.BYOK)
            .source(call.source())
            .workflowId(call.workflowId())
            .executionId(call.executionId())
            .inputTokens(call.inputTokens())
            .outputTokens(call.outputTokens())
            .totalTokens(call.totalTokens())
            .build();
    }

    /** {@code anthropic/claude-sonnet-5} → {@code claude-sonnet-5}. 카탈로그는 bare id만 등재한다. */
    static String normalizeModelId(String model) {
        String trimmed = model.trim();
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    private String defaultModel(AiProvider provider) {
        return providerRegistry.getAllProviders().stream()
            .filter(p -> p.provider().equals(provider.name()))
            .flatMap(p -> p.models().stream())
            .filter(ModelInfo::isDefault)
            .map(ModelInfo::id)
            .findFirst()
            .orElse(null);
    }

    private static AiProvider parseProvider(String provider) {
        if (isBlank(provider)) {
            return null;
        }
        try {
            return AiProvider.valueOf(provider.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a : !isBlank(b) ? b : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
