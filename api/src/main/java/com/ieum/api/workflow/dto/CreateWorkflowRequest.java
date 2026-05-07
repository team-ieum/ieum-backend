package com.ieum.api.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;

@Getter
public class CreateWorkflowRequest {

    @NotBlank
    private String name;

    private String description;

    @NotNull
    private List<NodeDto> nodes;

    @NotNull
    private List<EdgeDto> edges;
}
