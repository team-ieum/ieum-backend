package com.ieum.api.prompt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TestPromptTemplateRequest {

    @NotNull
    private UUID credentialId;

    @NotBlank
    private String provider;

    @NotBlank
    private String model;

    private Map<String, Object> variables;

    private Object parameters;
}
