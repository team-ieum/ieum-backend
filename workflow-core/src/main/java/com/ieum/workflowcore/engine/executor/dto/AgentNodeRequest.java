package com.ieum.workflowcore.engine.executor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    /** agent workflow_context 도구 입력 — {nodes:{<id>:{output,status,type}}, trigger} (nullable) */
    private Map<String, Object> workflowContext;
    /** 커스텀 MCP 서버 목록 — ieum-agent의 mcp_servers로 전달 (nullable) */
    @JsonProperty("mcp_servers")
    private List<McpServerRef> mcpServers;
}
