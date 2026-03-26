package com.ieum.api.prompt.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.prompt.domain.PromptTemplate;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PromptTemplateResponse {

    private final UUID id;
    private final String name;
    private final String description;
    private final String category;
    private final String systemMessage;
    private final String userMessageTemplate;
    private final Object inputVariables;
    private final Object defaultParameters;
    private final int version;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public static PromptTemplateResponse from(PromptTemplate template, ObjectMapper objectMapper) {
        try {
            JsonNode inputVariablesNode = objectMapper.readTree(template.getInputVariables());
            JsonNode defaultParametersNode = template.getDefaultParameters() != null
                ? objectMapper.readTree(template.getDefaultParameters())
                : null;

            return PromptTemplateResponse.builder()
                .id(template.getId())
                .name(template.getName())
                .description(template.getDescription())
                .category(template.getCategory())
                .systemMessage(template.getSystemMessage())
                .userMessageTemplate(template.getUserMessageTemplate())
                .inputVariables(inputVariablesNode)
                .defaultParameters(defaultParametersNode)
                .version(template.getVersion())
                .createdAt(template.getCreatedAt())
                .updatedAt(template.getUpdatedAt())
                .build();
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INVALID_PROMPT_TEMPLATE);
        }
    }
}
