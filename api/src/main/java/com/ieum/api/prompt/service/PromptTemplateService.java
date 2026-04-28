package com.ieum.api.prompt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.prompt.domain.PromptTemplate;
import com.ieum.api.prompt.dto.CreatePromptTemplateRequest;
import com.ieum.api.prompt.dto.UpdatePromptTemplateRequest;
import com.ieum.api.prompt.repository.PromptTemplateQueryRepository;
import com.ieum.api.prompt.repository.PromptTemplateRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.Map;
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
    private final PromptRenderer promptRenderer;

    @Transactional
    public PromptTemplate create(UUID userId, CreatePromptTemplateRequest request) {
        String inputVariables = toJsonString(request.getInputVariables(), "inputVariables");
        String defaultParameters = toJsonString(request.getDefaultParameters(), "defaultParameters");

        PromptTemplate template = PromptTemplate.builder()
            .userId(userId)
            .name(request.getName())
            .description(request.getDescription())
            .category(request.getCategory())
            .systemMessage(request.getSystemMessage())
            .userMessageTemplate(request.getUserMessageTemplate())
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
    public PromptTemplate update(UUID templateId, UUID userId, UpdatePromptTemplateRequest request) {
        PromptTemplate template = getByIdAndUserId(templateId, userId);
        String inputVariables = toJsonString(request.getInputVariables(), "inputVariables");
        String defaultParameters = toJsonString(request.getDefaultParameters(), "defaultParameters");

        template.update(
            request.getName(),
            request.getDescription(),
            request.getCategory(),
            request.getSystemMessage(),
            request.getUserMessageTemplate(),
            inputVariables,
            defaultParameters);

        return template;
    }

    @Transactional
    public void delete(UUID templateId, UUID userId) {
        PromptTemplate template = getByIdAndUserId(templateId, userId);
        // TODO Phase 3b: AI 노드에서 참조 중인지 체크 → TEMPLATE_IN_USE
        promptTemplateRepository.delete(template);
    }

    public TestResult testTemplate(UUID templateId, UUID userId, UUID credentialId,
            String provider, String model, Map<String, Object> variables, String parametersJson) {
        // TODO: Python 서비스(ieum-agent) 연동 후 구현 예정 (feat/agent-node-executor)
        throw new CustomException(ErrorCode.NOT_SUPPORTED);
    }

    private String toJsonString(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INVALID_PROMPT_TEMPLATE,
                fieldName + " JSON 형식이 올바르지 않습니다.");
        }
    }
}
