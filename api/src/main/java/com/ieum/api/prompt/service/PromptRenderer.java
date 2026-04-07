package com.ieum.api.prompt.service;

import com.ieum.api.prompt.domain.PromptTemplate;
import com.ieum.api.prompt.repository.PromptTemplateRepository;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PromptRenderer {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*(\\S+?)\\s*}}");

    private final PromptTemplateRepository promptTemplateRepository;

    public RenderedPrompt render(UUID templateId, String inlineSystemMessage, String inlineUserMessage,
            Map<String, Object> variables) {
        String systemMessage = inlineSystemMessage;
        String userMessage = inlineUserMessage;

        // 인라인으로 모두 채워지지 않은 경우에만 템플릿 조회
        if (templateId != null && (systemMessage == null || userMessage == null)) {
            PromptTemplate template = promptTemplateRepository.findById(templateId)
                .orElseThrow(() -> new CustomException(ErrorCode.TEMPLATE_NOT_FOUND));
            if (systemMessage == null) {
                systemMessage = template.getSystemMessage();
            }
            if (userMessage == null) {
                userMessage = template.getUserMessageTemplate();
            }
        }

        if (systemMessage == null || userMessage == null) {
            throw new CustomException(ErrorCode.INVALID_PROMPT_TEMPLATE, "프롬프트가 지정되지 않았습니다.");
        }

        systemMessage = substituteVariables(systemMessage, variables);
        userMessage = substituteVariables(userMessage, variables);

        return new RenderedPrompt(systemMessage, userMessage);
    }

    private String substituteVariables(String template, Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return template;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (variables.containsKey(key)) {
                Object value = variables.get(key);
                matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? "" : value.toString()));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
