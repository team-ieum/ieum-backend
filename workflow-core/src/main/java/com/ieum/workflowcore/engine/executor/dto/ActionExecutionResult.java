package com.ieum.workflowcore.engine.executor.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ieum-agent {@code POST /v1/actions/execute} 응답. AI 노드의 {@link AgentExecutionResult}와 달리
 * {@code output}이 문자열이 아니라 도구가 돌려준 dict이고, usage·metadata가 없다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActionExecutionResult {

    private boolean success;
    /** 성공 시 도구 반환 dict. 노드 출력이 되어 {@code {{nodes.<id>.output.<field>}}}로 참조된다 */
    private Map<String, Object> output;
    private String errorMessage;
    /**
     * 실패 원인 코드 — 도구 오류 {@code ACTION_TOOL_FAILED}, 가드 거부·도구 예외
     * {@code AGENT_EXECUTION_FAILED}, 타임아웃 {@code AGENT_TIMEOUT}. 없으면 BE는 {@code UNKNOWN}으로 본다.
     */
    private String errorCode;
}
