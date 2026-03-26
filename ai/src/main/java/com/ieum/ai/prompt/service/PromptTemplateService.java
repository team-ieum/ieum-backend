package com.ieum.ai.prompt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.ai.prompt.domain.PromptTemplate;
import com.ieum.ai.prompt.repository.PromptTemplateQueryRepository;
import com.ieum.ai.prompt.repository.PromptTemplateRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromptTemplateService {

    private final PromptTemplateRepository promptTemplateRepository;
    private final PromptTemplateQueryRepository promptTemplateQueryRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public PromptTemplate create(UUID userId, String name, String description, String category,
            String systemMessage, String userMessageTemplate,
            String inputVariables, String defaultParameters) {
        validateJsonFormat(inputVariables, "inputVariables");
        validateJsonFormat(defaultParameters, "defaultParameters");

        PromptTemplate template = PromptTemplate.builder()
            .userId(userId)
            .name(name)
            .description(description)
            .category(category)
            .systemMessage(systemMessage)
            .userMessageTemplate(userMessageTemplate)
            .inputVariables(inputVariables)
            .defaultParameters(defaultParameters)
            .version(1)
            .isPublic(false)
            .build();

        return promptTemplateRepository.save(template);
    }

    public PromptTemplate getByIdAndUserId(UUID templateId, UUID userId) {
        return promptTemplateRepository.findByIdAndUserId(templateId, userId)
            .orElseThrow(() -> new CustomException(ErrorCode.TEMPLATE_NOT_FOUND));
    }

    public Page<PromptTemplate> getList(UUID userId, String category, String search, Pageable pageable) {
        return promptTemplateQueryRepository.findByUserId(userId, category, search, pageable);
    }

    @Transactional
    public PromptTemplate update(UUID templateId, UUID userId, String name, String description,
            String category, String systemMessage, String userMessageTemplate,
            String inputVariables, String defaultParameters) {
        PromptTemplate template = getByIdAndUserId(templateId, userId);
        validateJsonFormat(inputVariables, "inputVariables");
        validateJsonFormat(defaultParameters, "defaultParameters");

        template.update(name, description, category, systemMessage, userMessageTemplate,
            inputVariables, defaultParameters);

        return template;
    }

    @Transactional
    public void delete(UUID templateId, UUID userId) {
        PromptTemplate template = getByIdAndUserId(templateId, userId);
        // TODO Phase 3b: AI 노드에서 참조 중인지 체크 → TEMPLATE_IN_USE
        promptTemplateRepository.delete(template);
    }

    private void validateJsonFormat(String json, String fieldName) {
        if (json == null) {
            return;
        }
        try {
            objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INVALID_PROMPT_TEMPLATE,
                fieldName + " JSON 형식이 올바르지 않습니다.");
        }
    }
}
