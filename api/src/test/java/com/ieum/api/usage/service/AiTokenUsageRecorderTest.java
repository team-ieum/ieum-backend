package com.ieum.api.usage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.api.credential.domain.AiProvider;
import com.ieum.api.provider.model.ModelInfo;
import com.ieum.api.provider.service.ProviderRegistry;
import com.ieum.api.usage.domain.AiTokenUsage;
import com.ieum.api.usage.domain.AiTokenUsage.KeySource;
import com.ieum.api.usage.domain.AiTokenUsage.UsageSource;
import com.ieum.api.usage.repository.AiTokenUsageRepository;
import com.ieum.api.usage.service.AiTokenUsageRecorder.UsageCall;
import com.ieum.workflowcore.engine.executor.AiUsageRecorder;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 H2 스키마에서 모델 결정 규칙과 트랜잭션 분리(REQUIRES_NEW)를 본다. 모델 ID는 카탈로그에서 읽는다. */
@SpringBootTest
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class AiTokenUsageRecorderTest {

    @Autowired private AiTokenUsageRecorder recorder;
    @Autowired private AiTokenUsageRepository repository;
    @Autowired private ProviderRegistry providerRegistry;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private AiUsageRecorder workflowRecorder;

    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    private UsageCall.UsageCallBuilder call(String provider) {
        return UsageCall.builder().userId(userId).provider(provider).source(UsageSource.WORKFLOW)
            .inputTokens(10).outputTokens(5).totalTokens(15);
    }

    private List<ModelInfo> catalog(AiProvider provider) {
        return providerRegistry.getAllProviders().stream()
            .filter(p -> p.provider().equals(provider.name())).findFirst().orElseThrow().models();
    }

    private String defaultModel(AiProvider provider) {
        return catalog(provider).stream().filter(ModelInfo::isDefault).findFirst().orElseThrow().id();
    }

    private String nonDefaultModel(AiProvider provider) {
        return catalog(provider).stream().filter(m -> !m.isDefault()).findFirst().orElseThrow().id();
    }

    private AiTokenUsage single() {
        List<AiTokenUsage> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    @DisplayName("요청 모델의 provider 접두사를 떼고 BYOK로 기록한다")
    void stripsPrefix() {
        String model = nonDefaultModel(AiProvider.CLAUDE);
        recorder.record(call("claude").requestedModel("anthropic/" + model).build());

        AiTokenUsage row = single();
        assertThat(row.getProvider()).isEqualTo(AiProvider.CLAUDE);
        assertThat(row.getModel()).isEqualTo(model);
        assertThat(row.getKeySource()).isEqualTo(KeySource.BYOK);
        assertThat(row.getTotalTokens()).isEqualTo(15);
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("요청 모델이 없으면 agent가 쓰는 provider 기본 모델(카탈로그 default)로 기록한다")
    void blankModelUsesCatalogDefault() {
        recorder.record(call("OPENAI").requestedModel(" ").build());

        assertThat(single().getModel()).isEqualTo(defaultModel(AiProvider.OPENAI));
    }

    @Test
    @DisplayName("베타 platform 키 — 요청 provider·모델과 무관하게 GEMINI 기본 모델, PLATFORM으로 기록한다")
    void platformKeyForcesGeminiDefault() {
        recorder.record(call("CLAUDE").requestedModel(nonDefaultModel(AiProvider.CLAUDE)).platformKey(true).build());

        AiTokenUsage row = single();
        assertThat(row.getProvider()).isEqualTo(AiProvider.GEMINI);
        assertThat(row.getModel()).isEqualTo(defaultModel(AiProvider.GEMINI));
        assertThat(row.getKeySource()).isEqualTo(KeySource.PLATFORM);
    }

    @Test
    @DisplayName("agent가 실어 준 실제 모델이 요청 모델보다 우선한다 — 카탈로그 밖 모델도 그대로")
    void reportedModelWins() {
        recorder.record(call("GEMINI").requestedModel(defaultModel(AiProvider.GEMINI))
            .reportedModel("gemini/gemini-legacy-x").build());

        assertThat(single().getModel()).isEqualTo("gemini-legacy-x");
    }

    @Test
    @DisplayName("usage 미보고 — 토큰은 null로 두고 행은 남긴다")
    void nullTokens() {
        recorder.record(UsageCall.builder().userId(userId).provider("CLAUDE").source(UsageSource.CHAT).build());

        AiTokenUsage row = single();
        assertThat(row.getInputTokens()).isNull();
        assertThat(row.getTotalTokens()).isNull();
    }

    @Test
    @DisplayName("알 수 없는 provider는 기록하지 않고 예외도 던지지 않는다")
    void unknownProviderSkipped() {
        assertThatCode(() -> recorder.record(call("MISTRAL").build())).doesNotThrowAnyException();
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("바깥 트랜잭션이 롤백돼도 사용량은 남는다(REQUIRES_NEW)")
    void survivesOuterRollback() {
        transactionTemplate.executeWithoutResult(tx -> {
            recorder.record(call("CLAUDE").build());
            tx.setRollbackOnly();
        });

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("워크플로우 포트 어댑터 — WORKFLOW로 기록하고, 없는 실행 ID면 workflowId는 null로 둔 채 행을 남긴다")
    void workflowPortAdapter() {
        UUID executionId = UUID.randomUUID();
        workflowRecorder.record(new AiUsageRecorder.AiUsageCall(
            userId, "CLAUDE", null, null, false, executionId, 1, 2, 3));

        AiTokenUsage row = single();
        assertThat(row.getSource()).isEqualTo(UsageSource.WORKFLOW);
        assertThat(row.getExecutionId()).isEqualTo(executionId);
        assertThat(row.getWorkflowId()).isNull();
        assertThat(row.getOutputTokens()).isEqualTo(2);
    }

    @Test
    @DisplayName("저장 실패(source 누락 → NOT NULL 위반)는 삼키고 바깥 트랜잭션을 깨지 않는다")
    void saveFailureIsSwallowed() {
        assertThatCode(() -> transactionTemplate.executeWithoutResult(tx ->
            recorder.record(call("CLAUDE").source(null).build())))
            .doesNotThrowAnyException();
        assertThat(repository.count()).isZero();
    }
}
