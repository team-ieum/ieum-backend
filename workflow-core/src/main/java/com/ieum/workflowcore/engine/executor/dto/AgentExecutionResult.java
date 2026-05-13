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
}
