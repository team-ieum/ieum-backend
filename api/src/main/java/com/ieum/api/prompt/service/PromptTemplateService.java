package com.ieum.api.prompt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.ai.adapter.ProviderAdapterFactory;
import com.ieum.ai.adapter.model.LlmRequest;
import com.ieum.ai.adapter.model.LlmResponse;
import com.ieum.ai.adapter.model.Message;
import com.ieum.ai.adapter.model.ModelParameters;
import com.ieum.api.credential.domain.UserAiCredential;
import com.ieum.api.credential.service.UserAiCredentialService;
import com.ieum.api.prompt.domain.PromptTemplate;
import com.ieum.api.prompt.dto.CreatePromptTemplateRequest;
import com.ieum.api.prompt.dto.UpdatePromptTemplateRequest;
import com.ieum.api.prompt.repository.PromptTemplateQueryRepository;
import com.ieum.api.prompt.repository.PromptTemplateRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.List;
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
    private final ProviderAdapterFactory providerAdapterFactory;
    private final UserAiCredentialService userAiCredentialService;

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
        PromptTemplate template = getByIdAndUserId(templateId, userId);

        // 이미 로드된 템플릿을 inline으로 전달 → DB 조회 중복 방지
        RenderedPrompt rendered = promptRenderer.render(
            null,
            template.getSystemMessage(),
            template.getUserMessageTemplate(),
            variables);

        UserAiCredential credential = userAiCredentialService.getByIdAndUserId(credentialId, userId);
        String decryptedApiKey = userAiCredentialService.decryptApiKey(credential);

        ModelParameters parameters = parseModelParameters(parametersJson);

        LlmRequest llmRequest = new LlmRequest(
            model,
            rendered.systemMessage(),
            List.of(Message.user(rendered.userMessage())),
            List.of(),
            parameters,
            decryptedApiKey
        );

        long start = System.currentTimeMillis();
        LlmResponse llmResponse = providerAdapterFactory.getAdapter(provider).chat(llmRequest);
        long durationMs = System.currentTimeMillis() - start;

        return new TestResult(llmResponse.textContent(), llmResponse.usage(), durationMs, rendered);
    }

    private ModelParameters parseModelParameters(String parametersJson) {
        if (parametersJson == null) {
            return new ModelParameters(null, null, null, null);
        }
        try {
            return objectMapper.readValue(parametersJson, ModelParameters.class);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "parameters JSON 형식이 올바르지 않습니다.");
        }
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
