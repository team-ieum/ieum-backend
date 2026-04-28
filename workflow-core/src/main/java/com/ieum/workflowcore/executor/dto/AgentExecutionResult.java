package com.ieum.workflowcore.executor.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentExecutionResult {

    // 실행 성공 여부
    private boolean success;

    // 실행 결과 텍스트 (nullable)
    private String output;

    // 부가 메타데이터 - 토큰 사용량 등 (nullable)
    private Map<String, Object> metadata;

    // 에러 메시지 (nullable)
    private String errorMessage;
}
