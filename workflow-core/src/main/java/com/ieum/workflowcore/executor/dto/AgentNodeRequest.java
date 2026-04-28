package com.ieum.workflowcore.executor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentNodeRequest {

    // 실행 노드 ID
    private String nodeId;

    // 프롬프트 템플릿 ID (nullable)
    private String promptTemplateId;

    // 변수 치환이 완료된 프롬프트
    private String renderedPrompt;

    // ADK tool 목록 (nullable)
    private List<Map<String, Object>> tools;
}
