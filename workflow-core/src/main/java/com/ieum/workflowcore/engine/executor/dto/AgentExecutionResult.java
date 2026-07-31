package com.ieum.workflowcore.engine.executor.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentExecutionResult {

    private boolean success;
    private String output;
    private Map<String, Object> metadata;
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

    /** ieum-agent 응답의 usage 필드. 베타 플랫폼 키 토큰 쿼터 사후 차감에 사용한다. */
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
