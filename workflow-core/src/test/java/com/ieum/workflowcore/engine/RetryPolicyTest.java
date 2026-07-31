package com.ieum.workflowcore.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.workflowcore.config.RetryProperties;
import com.ieum.workflowcore.domain.enums.NodeType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryProperties defaults = new RetryProperties();

    private static Map<String, Object> configWithRetry(Map<String, Object> retry) {
        Map<String, Object> config = new HashMap<>();
        config.put("retry", retry);
        return config;
    }

    @Nested
    @DisplayName("기본값")
    class Defaults {

        @Test
        @DisplayName("AI 노드는 retry 선언이 없어도 3회 시도한다")
        void aiNodeRetriesByDefault() {
            RetryPolicy policy = RetryPolicy.from(null, NodeType.AI, defaults);

            assertThat(policy.maxAttempts()).isEqualTo(3);
            assertThat(policy.isDisabled()).isFalse();
        }

        @Test
        @DisplayName("AI 외 노드는 retry 선언이 없으면 재시도하지 않는다")
        void nonAiNodeDoesNotRetryByDefault() {
            assertThat(RetryPolicy.from(null, NodeType.HTTP, defaults).isDisabled()).isTrue();
            assertThat(RetryPolicy.from(null, NodeType.TRANSFORM, defaults).isDisabled()).isTrue();
        }

        @Test
        @DisplayName("부수효과가 있는 HTTP·AI 노드는 멱등성 헤더 주입이 기본이고 그 외는 NONE이다")
        void idempotencyDefaultDependsOnNodeType() {
            assertThat(RetryPolicy.from(null, NodeType.HTTP, defaults).idempotency())
                .isEqualTo(IdempotencyMode.HEADER);
            // AI 노드는 기본 재시도 3회 + 도구 사용이라 NONE이면 부수효과가 회차마다 중복된다
            assertThat(RetryPolicy.from(null, NodeType.AI, defaults).idempotency())
                .isEqualTo(IdempotencyMode.HEADER);
            assertThat(RetryPolicy.from(null, NodeType.TRANSFORM, defaults).idempotency())
                .isEqualTo(IdempotencyMode.NONE);
            assertThat(RetryPolicy.from(null, NodeType.CONDITION, defaults).idempotency())
                .isEqualTo(IdempotencyMode.NONE);
        }

        @Test
        @DisplayName("AI 노드는 기본 재시도와 헤더 멱등이 함께 켜져 실제로 헤더가 붙는다")
        void aiNodeDefaultsCombineRetryAndHeader() {
            RetryPolicy policy = RetryPolicy.from(null, NodeType.AI, defaults);

            // AgentNodeExecutor가 헤더를 붙이는 조건은 usesHeader() && !isDisabled() 둘 다이다
            assertThat(policy.isDisabled()).isFalse();
            assertThat(policy.idempotency().usesHeader()).isTrue();
        }
    }

    @Nested
    @DisplayName("노드 config 파싱")
    class Parsing {

        @Test
        @DisplayName("선언된 값이 기본값을 덮어쓴다")
        void nodeConfigOverridesDefaults() {
            RetryPolicy policy = RetryPolicy.from(configWithRetry(Map.of(
                "maxAttempts", 5,
                "backoffMs", 250,
                "multiplier", 3.0,
                "maxBackoffMs", 10_000,
                "jitter", false,
                "timeoutMs", 4_000,
                "modelFallback", List.of("claude-haiku-4-5"),
                "idempotency", "both"
            )), NodeType.AI, defaults);

            assertThat(policy.maxAttempts()).isEqualTo(5);
            assertThat(policy.backoffMs()).isEqualTo(250);
            assertThat(policy.multiplier()).isEqualTo(3.0);
            assertThat(policy.maxBackoffMs()).isEqualTo(10_000);
            assertThat(policy.jitter()).isFalse();
            assertThat(policy.timeoutMs()).isEqualTo(4_000L);
            assertThat(policy.modelFallback()).containsExactly("claude-haiku-4-5");
            assertThat(policy.idempotency()).isEqualTo(IdempotencyMode.BOTH);
        }

        @Test
        @DisplayName("retry가 Map이 아니면 기본값으로 떨어진다")
        void nonMapRetryFallsBackToDefaults() {
            RetryPolicy policy = RetryPolicy.from(configWithRetry(null), NodeType.AI, defaults);
            assertThat(policy.maxAttempts()).isEqualTo(3);

            Map<String, Object> broken = new HashMap<>();
            broken.put("retry", "3회");
            assertThat(RetryPolicy.from(broken, NodeType.AI, defaults).maxAttempts()).isEqualTo(3);
        }

        @Test
        @DisplayName("알 수 없는 idempotency 값은 실행을 막지 않고 기본값이 된다")
        void unknownIdempotencyFallsBack() {
            RetryPolicy policy = RetryPolicy.from(
                configWithRetry(Map.of("idempotency", "MAGIC")), NodeType.HTTP, defaults);

            assertThat(policy.idempotency()).isEqualTo(IdempotencyMode.HEADER);
        }

        @Test
        @DisplayName("modelFallback의 문자열 아닌 값·공백은 걸러낸다")
        void modelFallbackFiltersJunk() {
            Map<String, Object> retry = new HashMap<>();
            retry.put("modelFallback", java.util.Arrays.asList("haiku", "  ", 42, null, "sonnet"));

            RetryPolicy policy = RetryPolicy.from(configWithRetry(retry), NodeType.AI, defaults);

            assertThat(policy.modelFallback()).containsExactly("haiku", "sonnet");
        }
    }

    @Nested
    @DisplayName("사용자 입력 상·하한 강제")
    class Clamping {

        @Test
        @DisplayName("maxAttempts는 1 미만·상한 초과가 잘린다")
        void attemptsAreClamped() {
            assertThat(RetryPolicy.from(configWithRetry(Map.of("maxAttempts", 0)),
                NodeType.AI, defaults).maxAttempts()).isEqualTo(1);
            assertThat(RetryPolicy.from(configWithRetry(Map.of("maxAttempts", 9_999)),
                NodeType.AI, defaults).maxAttempts()).isEqualTo(RetryPolicy.MAX_ATTEMPTS_CAP);
        }

        @Test
        @DisplayName("multiplier는 1.0 미만으로 내려가지 않는다")
        void multiplierNeverShrinksBackoff() {
            assertThat(RetryPolicy.from(configWithRetry(Map.of("multiplier", 0.1)),
                NodeType.AI, defaults).multiplier()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("maxBackoffMs가 backoffMs보다 작으면 backoffMs까지 끌어올린다")
        void maxBackoffNeverBelowBackoff() {
            RetryPolicy policy = RetryPolicy.from(configWithRetry(Map.of(
                "backoffMs", 5_000, "maxBackoffMs", 100)), NodeType.AI, defaults);

            assertThat(policy.maxBackoffMs()).isEqualTo(5_000);
        }

        @Test
        @DisplayName("timeoutMs는 양수가 아니면 null이다")
        void nonPositiveTimeoutBecomesNull() {
            assertThat(RetryPolicy.from(configWithRetry(Map.of("timeoutMs", 0)),
                NodeType.AI, defaults).timeoutMs()).isNull();
        }
    }

    @Nested
    @DisplayName("재시도 판정과 모델 선택")
    class Decisions {

        @Test
        @DisplayName("재시도 가능 원인만 재시도한다")
        void onlyRetryableKindsRetry() {
            RetryPolicy policy = RetryPolicy.from(null, NodeType.AI, defaults);

            assertThat(policy.retryable(FailureKind.RATE_LIMIT)).isTrue();
            assertThat(policy.retryable(FailureKind.TIMEOUT)).isTrue();
            assertThat(policy.retryable(FailureKind.CLIENT_ERROR)).isFalse();
            assertThat(policy.retryable(FailureKind.UNKNOWN)).isFalse();
            assertThat(policy.retryable(null)).isFalse();
        }

        @Test
        @DisplayName("재시도가 꺼져 있으면 원인과 무관하게 재시도하지 않는다")
        void disabledPolicyNeverRetries() {
            RetryPolicy policy = RetryPolicy.from(null, NodeType.HTTP, defaults);

            assertThat(policy.retryable(FailureKind.RATE_LIMIT)).isFalse();
        }

        @Test
        @DisplayName("1회차는 원 모델, 2회차부터 fallback을 차례로 쓰고 목록을 넘으면 원 모델을 유지한다")
        void modelFallbackWalksByAttempt() {
            RetryPolicy policy = RetryPolicy.from(configWithRetry(Map.of(
                "maxAttempts", 4,
                "modelFallback", List.of("haiku", "sonnet")
            )), NodeType.AI, defaults);

            assertThat(policy.modelForAttempt(1, "gemini-3.5-flash")).isEqualTo("gemini-3.5-flash");
            assertThat(policy.modelForAttempt(2, "gemini-3.5-flash")).isEqualTo("haiku");
            assertThat(policy.modelForAttempt(3, "gemini-3.5-flash")).isEqualTo("sonnet");
            assertThat(policy.modelForAttempt(4, "gemini-3.5-flash")).isEqualTo("gemini-3.5-flash");
        }
    }

    @Nested
    @DisplayName("실패 분류")
    class Classification {

        @Test
        @DisplayName("agent errorCode를 재시도 여부로 매핑한다")
        void agentErrorCodesMapToKinds() {
            assertThat(FailureClassifier.fromAgentErrorCode("RATE_LIMITED"))
                .isEqualTo(FailureKind.RATE_LIMIT);
            assertThat(FailureClassifier.fromAgentErrorCode("AGENT_TIMEOUT"))
                .isEqualTo(FailureKind.TIMEOUT);
            assertThat(FailureClassifier.fromAgentErrorCode("AGENT_TOOL_NOT_CALLED"))
                .isEqualTo(FailureKind.CLIENT_ERROR);
        }

        @Test
        @DisplayName("agent가 errorCode를 안 내려주면 UNKNOWN이라 재시도하지 않는다")
        void missingAgentErrorCodeIsUnknown() {
            assertThat(FailureClassifier.fromAgentErrorCode(null)).isEqualTo(FailureKind.UNKNOWN);
            assertThat(FailureClassifier.fromAgentErrorCode("")).isEqualTo(FailureKind.UNKNOWN);
            assertThat(FailureClassifier.fromAgentErrorCode("무언가")).isEqualTo(FailureKind.UNKNOWN);
        }

        @Test
        @DisplayName("HTTP 상태 코드를 분류한다")
        void httpStatusesMapToKinds() {
            assertThat(FailureClassifier.fromHttpStatus(429)).isEqualTo(FailureKind.RATE_LIMIT);
            assertThat(FailureClassifier.fromHttpStatus(503)).isEqualTo(FailureKind.SERVER_ERROR);
            assertThat(FailureClassifier.fromHttpStatus(404)).isEqualTo(FailureKind.CLIENT_ERROR);
        }

        @Test
        @DisplayName("여러 겹으로 감싸인 원인 예외까지 따라 내려가 분류한다")
        void wrappedCausesAreClassified() {
            Exception wrapped = new RuntimeException("bl",
                new IllegalStateException("mid", new java.net.SocketTimeoutException("read timeout")));

            assertThat(FailureClassifier.fromException(wrapped)).isEqualTo(FailureKind.TIMEOUT);
            assertThat(FailureClassifier.fromException(new RuntimeException("사유 없음")))
                .isEqualTo(FailureKind.UNKNOWN);
        }

        @Test
        @DisplayName("실패 분류를 안 주면 UNKNOWN으로 채워져 재시도되지 않는다")
        void legacyFailureDefaultsToUnknown() {
            assertThat(ExecutorResult.failure("어쩌구", 10).getFailureKind())
                .isEqualTo(FailureKind.UNKNOWN);
        }
    }
}
