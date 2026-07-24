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
