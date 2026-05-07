package com.ieum.api.workflow.dto;

import java.util.Map;
import lombok.Getter;

@Getter
public class NodeDto {
    private String id;
    private String type;
    private String label;
    private Map<String, Object> config;
}
