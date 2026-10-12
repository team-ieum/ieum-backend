package com.ieum.workflowcore.engine.executor.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentExecutionResult {

    private boolean success;
    /**
     * 노드 출력 원값. 구조화 출력이면 선언한 칸의 객체(Jackson이 {@code LinkedHashMap}으로 푼다),
     * 옛 agent·기본 칸이면 문자열. {@code JsonNode}로 받지 않는다 — 커서 경로 해석·마스킹이
     * Map·List·String만 따라간다. 노드 출력 조립은 {@code AgentNodeExecutor}가 한다.
     */
    private Object output;
    private String errorMessage;
    private Usage usage;

    /**
     * 실패 원인 코드. ieum-agent {@code ErrorCode} 이름과 1:1이다
     * (예: {@code RATE_LIMITED}, {@code AGENT_TIMEOUT}, {@code AGENT_EXECUTION_FAILED}).
     *
     * <p>agent가 아직 이 필드를 내려주지 않으면 null이며, 그 경우 BE는 실패를
     * {@code UNKNOWN}으로 보고 재시도하지 않는다 — 어느 쪽을 먼저 배포해도 안전하다.
     */
    private String errorCode;

    /** ieum-agent 응답의 usage 필드. node_runs 토큰 기록(실패 응답 포함)과 베타 토큰 사후 차감(성공만)에 쓴다. */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
    }
}
