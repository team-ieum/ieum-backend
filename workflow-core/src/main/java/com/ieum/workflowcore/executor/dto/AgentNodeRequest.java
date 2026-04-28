package com.ieum.workflowcore.executor.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@Builder
public class AgentNodeRequest {

    private String nodeId;
    private String promptTemplateId;
    private String renderedPrompt;
    private List<Map<String, Object>> tools;
}
