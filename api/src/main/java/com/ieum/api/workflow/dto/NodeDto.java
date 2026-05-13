package com.ieum.api.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.Getter;

@Getter
public class NodeDto {
    @NotBlank(message = "노드 id는 필수입니다.")
    private String id;

    @NotBlank(message = "노드 type은 필수입니다.")
    private String type;

    private String label;
    private Map<String, Object> config;
}
