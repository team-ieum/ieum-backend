package com.ieum.workflowcore.engine;

import com.ieum.workflowcore.config.RetryProperties;
import com.ieum.workflowcore.domain.enums.NodeType;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 노드 단위 재시도 정책. 노드 config의 {@code retry} 객체에서 파싱된다.
 *
 * <pre>{@code
 * "retry": {
 *   "maxAttempts": 3, "backoffMs": 1000, "multiplier": 2.0,
 *   "maxBackoffMs": 30000, "jitter": true, "timeoutMs": null,
 *   "modelFallback": ["claude-haiku-4-5"], "idempotency": "HEADER"
 * }
 * }</pre>
 *
 * <p>선언이 없으면 {@link RetryProperties}의 기본값이 적용된다. AI 노드만 기본적으로
 * 재시도하며(LLM rate-limit·timeout이 잦다), 그 외 노드는 명시 선언이 있어야 재시도한다
 * — HTTP 노드는 멱등성을 보장할 수 없어 기본 재시도가 위험하다.
 *
 * <p>노드 config는 사용자가 편집하는 값이므로 모든 필드에 상·하한을 강제한다.
 */
public record RetryPolicy(
    int maxAttempts,
    long backoffMs,
    double multiplier,
    long maxBackoffMs,
    boolean jitter,
    Long timeoutMs,
    List<String> modelFallback,
    IdempotencyMode idempotency
) {

    /** 사용자 config가 요구할 수 있는 최대 시도 횟수 — 오타·악의적 설정으로 인한 폭주 차단 */
    public static final int MAX_ATTEMPTS_CAP = 10;
    /** 단일 백오프 대기의 절대 상한(10분) */
    public static final long BACKOFF_CAP_MS = 600_000L;
    private static final double MULTIPLIER_CAP = 10.0;

    /** 재시도하지 않음 */
    public boolean isDisabled() {
        return maxAttempts <= 1;
    }

    /** 이 실패 원인으로 재시도할 수 있는지 */
    public boolean retryable(FailureKind kind) {
        return !isDisabled() && kind != null && kind.isRetryable();
    }

    /**
     * attempt회차 실패 후 다음 시도까지 대기할 ms. attempt는 1부터.
     *
     * <p>기본 대기 = {@code backoffMs * multiplier^(attempt-1)}, {@code maxBackoffMs}로 상한 clamp.
     * {@link #jitter}가 true면 full jitter로 {@code [0, computed]} 사이 균등 난수를 반환한다.
     */
    public long backoffMillis(int attempt, java.util.random.RandomGenerator random) {
        double raw = backoffMs * Math.pow(multiplier, attempt - 1);
        long computed = (long) Math.min(raw, (double) maxBackoffMs);
        if (!jitter) {
            return computed;
        }
        // nextLong(0, 1)은 항상 0이라 그대로 둬도 되지만 인자 조건(bound>0) 위반을 피하도록 방어
        return computed == 0 ? 0 : random.nextLong(0, computed + 1);
    }

    /**
     * 이 회차에 사용할 모델을 반환한다. 1회차는 원 모델, 2회차부터 fallback 목록을 차례로 쓴다.
     * 목록을 넘어서면 원 모델을 유지한다.
     *
     * @param attempt      1부터 시작하는 시도 회차
     * @param originalModel 노드 config에 선언된 원 모델(null 가능)
     */
    public String modelForAttempt(int attempt, String originalModel) {
        int index = attempt - 2;
        if (index < 0 || index >= modelFallback.size()) {
            return originalModel;
        }
        return modelFallback.get(index);
    }

    /**
     * 노드 config에서 재시도 정책을 파싱한다. 값이 없거나 형식이 어긋나면 기본값을 쓴다.
     *
     * @param nodeConfig 노드 config (null 허용)
     * @param nodeType   기본 시도 횟수 결정에 사용
     */
    @SuppressWarnings("unchecked")
    public static RetryPolicy from(Map<String, Object> nodeConfig, NodeType nodeType,
                                   RetryProperties defaults) {
        int defaultAttempts = nodeType == NodeType.AI
            ? defaults.getAiMaxAttempts()
            : defaults.getDefaultMaxAttempts();

        Object raw = nodeConfig == null ? null : nodeConfig.get("retry");
        if (!(raw instanceof Map)) {
            return new RetryPolicy(
                clampAttempts(defaultAttempts), defaults.getBackoffMs(), defaults.getMultiplier(),
                defaults.getMaxBackoffMs(), defaults.isJitter(), null, List.of(),
                defaultIdempotency(nodeType));
        }
        Map<String, Object> retry = (Map<String, Object>) raw;

        long backoffMs = clamp(longOr(retry.get("backoffMs"), defaults.getBackoffMs()), 0, BACKOFF_CAP_MS);
        long maxBackoffMs = clamp(
            longOr(retry.get("maxBackoffMs"), defaults.getMaxBackoffMs()), 0, BACKOFF_CAP_MS);

        return new RetryPolicy(
            clampAttempts((int) longOr(retry.get("maxAttempts"), defaultAttempts)),
            backoffMs,
            clampMultiplier(doubleOr(retry.get("multiplier"), defaults.getMultiplier())),
            Math.max(maxBackoffMs, backoffMs),
            boolOr(retry.get("jitter"), defaults.isJitter()),
            positiveLongOrNull(retry.get("timeoutMs")),
            stringList(retry.get("modelFallback")),
            idempotency(retry.get("idempotency"), nodeType)
        );
    }

    /** HTTP 노드는 부수효과가 있어 헤더 주입을 기본으로 켠다(지원하는 상대에겐 실효가 있다). */
    private static IdempotencyMode defaultIdempotency(NodeType nodeType) {
        return nodeType == NodeType.HTTP ? IdempotencyMode.HEADER : IdempotencyMode.NONE;
    }

    private static IdempotencyMode idempotency(Object value, NodeType nodeType) {
        if (value instanceof String s && !s.isBlank()) {
            try {
                return IdempotencyMode.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 알 수 없는 값은 기본값으로 떨어뜨린다 — 실행을 막을 이유는 없다
            }
        }
        return defaultIdempotency(nodeType);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return ((List<Object>) list).stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .filter(s -> !s.isBlank())
            .toList();
    }

    private static int clampAttempts(int value) {
        return (int) clamp(value, 1, MAX_ATTEMPTS_CAP);
    }

    private static double clampMultiplier(double value) {
        if (Double.isNaN(value) || value < 1.0) {
            return 1.0;
        }
        return Math.min(value, MULTIPLIER_CAP);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long longOr(Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }

    private static double doubleOr(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private static boolean boolOr(Object value, boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }

    private static Long positiveLongOrNull(Object value) {
        if (value instanceof Number n && n.longValue() > 0) {
            return n.longValue();
        }
        return null;
    }
}
