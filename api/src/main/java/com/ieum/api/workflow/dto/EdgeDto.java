package com.ieum.api.workflow.dto;

import lombok.Getter;

@Getter
public class EdgeDto {
    private String source;
    private String target;
    private String conditionType;
}
