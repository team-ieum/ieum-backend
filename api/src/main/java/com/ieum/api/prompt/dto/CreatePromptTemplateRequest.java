package com.ieum.api.prompt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CreatePromptTemplateRequest {

    @NotBlank
    @Size(max = 100)
    private String name;

    private String description;

    @Size(max = 50)
    private String category;

    @NotBlank
    private String systemMessage;

    @NotBlank
    private String userMessageTemplate;

    @NotNull
    private Object inputVariables;

    private Object defaultParameters;
}
