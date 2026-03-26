package com.ieum.ai.adapter.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ieum.ai.adapter.ProviderAdapter;
import com.ieum.ai.adapter.model.LlmRequest;
import com.ieum.ai.adapter.model.LlmResponse;
import com.ieum.ai.adapter.model.Message;
import com.ieum.ai.adapter.model.ModelInfo;
import com.ieum.ai.adapter.model.ModelParameters;
import com.ieum.ai.adapter.model.Role;
import com.ieum.ai.adapter.model.StopReason;
import com.ieum.ai.adapter.model.TokenUsage;
import com.ieum.ai.adapter.model.ToolDefinition;
import com.ieum.ai.adapter.model.ToolUseRequest;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeAdapter implements ProviderAdapter {

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 1024;

    // Claude API 필드명
    private static final String FIELD_ROLE = "role";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_INPUT = "input";
    private static final String FIELD_INPUT_SCHEMA = "input_schema";
    private static final String FIELD_DESCRIPTION = "description";

    // Claude API role 값
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    // Claude API content type 값
    private static final String CONTENT_TYPE_TEXT = "text";
    private static final String CONTENT_TYPE_TOOL_USE = "tool_use";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public String getProvider() {
        return "CLAUDE";
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        HttpHeaders headers = buildHeaders(request.decryptedApiKey());
        String requestBody = buildRequestBody(request);
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                CLAUDE_API_URL, HttpMethod.POST, entity, String.class);
            return parseResponse(response.getBody());
        } catch (HttpClientErrorException e) {
            handleClientError(e);
            throw new CustomException(ErrorCode.PROVIDER_ERROR); // unreachable
        } catch (HttpServerErrorException e) {
            log.error("Claude API 서버 오류: {}", e.getStatusCode());
            throw new CustomException(ErrorCode.PROVIDER_ERROR);
        } catch (ResourceAccessException e) {
            log.error("Claude API 타임아웃: {}", e.getMessage());
            throw new CustomException(ErrorCode.PROVIDER_TIMEOUT);
        } catch (Exception e) {
            log.error("Claude API 응답 파싱 오류: {}", e.getMessage());
            throw new CustomException(ErrorCode.PROVIDER_ERROR);
        }
    }

    @Override
    public boolean validateCredential(String decryptedKey) {
        LlmRequest testRequest = new LlmRequest(
            "claude-haiku-4-5-20251001",
            "Respond with only: OK",
            List.of(Message.user("OK")),
            List.of(),
            new ModelParameters(null, 5, null, null),
            decryptedKey
        );
        try {
            chat(testRequest);
            return true;
        } catch (CustomException e) {
            if (e.getErrorCode() == ErrorCode.INVALID_API_KEY
                || e.getErrorCode() == ErrorCode.CREDENTIAL_NO_BILLING) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public List<ModelInfo> listModels() {
        return List.of(
            new ModelInfo("claude-opus-4-6", "Claude Opus 4.6"),
            new ModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6"),
            new ModelInfo("claude-haiku-4-5-20251001", "Claude Haiku 4.5")
        );
    }

    private HttpHeaders buildHeaders(String decryptedApiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", decryptedApiKey);
        headers.set("anthropic-version", ANTHROPIC_VERSION);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String buildRequestBody(LlmRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        body.put("system", request.systemMessage());

        ModelParameters params = request.parameters();
        body.put("max_tokens", params != null && params.maxTokens() != null
            ? params.maxTokens() : DEFAULT_MAX_TOKENS);
        if (params != null) {
            if (params.temperature() != null) body.put("temperature", params.temperature());
            if (params.topP() != null) body.put("top_p", params.topP());
        }

        ArrayNode messagesNode = body.putArray("messages");
        for (Message msg : request.messages()) {
            ObjectNode msgNode = messagesNode.addObject();
            msgNode.put(FIELD_ROLE, msg.role() == Role.USER ? ROLE_USER : ROLE_ASSISTANT);
            msgNode.put(FIELD_CONTENT, msg.content());
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode toolsNode = body.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                ObjectNode toolNode = toolsNode.addObject();
                toolNode.put(FIELD_NAME, tool.name());
                toolNode.put(FIELD_DESCRIPTION, tool.description());
                toolNode.set(FIELD_INPUT_SCHEMA, tool.inputSchema());
            }
        }

        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.PROVIDER_ERROR);
        }
    }

    private LlmResponse parseResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);

        String id = root.path("id").asText();
        StopReason stopReason = parseStopReason(root.path("stop_reason").asText());

        String textContent = null;
        List<ToolUseRequest> toolUses = new ArrayList<>();

        for (JsonNode item : root.path(FIELD_CONTENT)) {
            String type = item.path(FIELD_TYPE).asText();
            if (CONTENT_TYPE_TEXT.equals(type)) {
                textContent = item.path(FIELD_TEXT).asText();
            } else if (CONTENT_TYPE_TOOL_USE.equals(type)) {
                toolUses.add(new ToolUseRequest(
                    item.path("id").asText(),
                    item.path(FIELD_NAME).asText(),
                    item.path(FIELD_INPUT)));
            }
        }

        JsonNode usageNode = root.path("usage");
        TokenUsage usage = new TokenUsage(
            usageNode.path("input_tokens").asInt(),
            usageNode.path("output_tokens").asInt());

        return new LlmResponse(id, stopReason, textContent, toolUses, usage);
    }

    private StopReason parseStopReason(String value) {
        return switch (value) {
            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            default -> StopReason.END_TURN;
        };
    }

    private void handleClientError(HttpClientErrorException e) {
        int status = e.getStatusCode().value();
        switch (status) {
            case 401, 403 -> throw new CustomException(ErrorCode.INVALID_API_KEY);
            case 402 -> throw new CustomException(ErrorCode.CREDENTIAL_NO_BILLING);
            case 429 -> throw new CustomException(ErrorCode.PROVIDER_RATE_LIMITED);
            default -> throw new CustomException(ErrorCode.PROVIDER_ERROR);
        }
    }
}
