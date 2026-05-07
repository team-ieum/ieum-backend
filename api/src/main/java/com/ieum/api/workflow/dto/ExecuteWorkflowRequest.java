package com.ieum.api.workflow.dto;

import java.util.Map;
import lombok.Getter;

@Getter
public class ExecuteWorkflowRequest {
    private Map<String, Object> triggerData;
}
