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

    public static ExecutorResult success(Map<String, Object> output, long durationMs) {
        return new ExecutorResult(true, output, null, durationMs);
    }

    public static ExecutorResult failure(String errorMessage, long durationMs) {
        return new ExecutorResult(false, null, errorMessage, durationMs);
    }
}
