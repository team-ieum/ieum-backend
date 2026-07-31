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

    /** 노드 실행 1회의 토큰 사용량. agent 응답에 usage가 없으면 각 필드가 null일 수 있다. */
    public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {}

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
        return new ExecutorResult(false, null, errorMessage, durationMs, null,
            kind == null ? FailureKind.UNKNOWN : kind);
    }
}
