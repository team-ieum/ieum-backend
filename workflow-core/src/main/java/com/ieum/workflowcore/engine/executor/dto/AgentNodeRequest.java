package com.ieum.workflowcore.engine.executor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentNodeRequest {

    private String nodeId;
    private String promptTemplateId;
    private String renderedPrompt;
    /** 시스템 메시지 — LLM에 전달할 역할/페르소나 지시문 (nullable) */
    private String systemMessage;
    /** 사용할 LLM 모델명 (예: claude-3-5-sonnet-20241022) (nullable) */
    private String model;
    /** 에이전트 실행 타입 (simple / react) (nullable, 기본값: simple) */
    private String agentType;
    private List<Map<String, Object>> tools;
    /** 이전 노드들의 output 전체 — Python ieum-agent의 workflow_context 툴에 전달 (nullable) */
    private Map<String, Map<String, Object>> workflowContext;
}
