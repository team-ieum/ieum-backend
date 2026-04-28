package com.ieum.workflowcore.executor.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
public class AgentExecutionResult {

    private boolean success;
    private String output;
    private Map<String, Object> metadata;
    private String errorMessage;
}
