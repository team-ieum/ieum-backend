package com.ieum.workflowcore.engine;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 노드 실행 결과. NodeExecutor 구현체가 반환하는 표준 응답 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutorResult {

    private boolean success;
    /** 이후 노드가 {{nodes.<id>.output.<field>}} 로 참조할 출력값 */
    private Map<String, Object> output;
    /** 실패 시 오류 메시지, 성공 시 null */
    private String errorMessage;
    private long durationMs;
    /** LLM 토큰 사용량. AI 노드만 채워지고 그 외 노드는 null */
    private TokenUsage usage;
    /** 실패 원인 분류(재시도 판단용). 성공 시 null, 분류 근거가 없으면 {@link FailureKind#UNKNOWN} */
    private FailureKind failureKind;

    /** 노드 실행의 토큰 사용량. agent 응답에 usage가 없으면 각 필드가 null일 수 있다. */
    public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {

        /** 재시도 회차 합산. 한쪽이 null이면 다른 쪽을 그대로 — 필드의 null도 0이 아니라 "모름"으로 남긴다. */
        public static TokenUsage sum(TokenUsage a, TokenUsage b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return new TokenUsage(add(a.promptTokens, b.promptTokens),
                add(a.completionTokens, b.completionTokens), add(a.totalTokens, b.totalTokens));
        }

        // 삼항식(x == null ? y : y == null ? x : x + y)은 쓰지 않는다 — 식 타입이 int로 승격돼
        // add(null, null)이 언박싱 NPE(JLS 15.25).
        private static Integer add(Integer x, Integer y) {
            if (x == null) {
                return y;
            }
            if (y == null) {
                return x;
            }
            return x + y;
        }
    }

    public static ExecutorResult success(Map<String, Object> output, long durationMs) {
        return new ExecutorResult(true, output, null, durationMs, null, null);
    }

    public static ExecutorResult success(Map<String, Object> output, long durationMs, TokenUsage usage) {
        return new ExecutorResult(true, output, null, durationMs, usage, null);
    }

    /** 실패 원인을 분류할 근거가 없는 경우. 재시도하지 않는다. */
    public static ExecutorResult failure(String errorMessage, long durationMs) {
        return failure(errorMessage, durationMs, FailureKind.UNKNOWN);
    }

    public static ExecutorResult failure(String errorMessage, long durationMs, FailureKind kind) {
        return failure(errorMessage, durationMs, kind, null);
    }

    /** 실패했지만 토큰은 이미 쓴 경우(agent 실패 응답의 usage) — 런타임이 node_runs에 기록한다. */
    public static ExecutorResult failure(String errorMessage, long durationMs, FailureKind kind,
                                         TokenUsage usage) {
        return new ExecutorResult(false, null, errorMessage, durationMs, usage,
            kind == null ? FailureKind.UNKNOWN : kind);
    }
}
