package com.ieum.api.prompt.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.prompt.domain.PromptTemplate;
import com.ieum.api.prompt.dto.CreatePromptTemplateRequest;
import com.ieum.api.prompt.dto.PromptTemplateResponse;
import com.ieum.api.prompt.dto.TestPromptTemplateRequest;
import com.ieum.api.prompt.dto.TestPromptTemplateResponse;
import com.ieum.api.prompt.dto.TestPromptTemplateResponse.RenderedPromptDto;
import com.ieum.api.prompt.dto.TestPromptTemplateResponse.TokenUsageDto;
import com.ieum.api.prompt.dto.UpdatePromptTemplateRequest;
import com.ieum.api.prompt.service.PromptTemplateService;
import com.ieum.api.prompt.service.TestResult;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import com.ieum.common.dto.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/prompt-templates")
@RequiredArgsConstructor
public class PromptTemplateController implements PromptTemplateControllerDocs {

    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<ApiResponse<PromptTemplateResponse>> create(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @RequestBody @Valid CreatePromptTemplateRequest request) {

        PromptTemplate template = promptTemplateService.create(userDetails.getId(), request);
        return ResponseEntity.status(201)
            .body(ApiResponse.created(PromptTemplateResponse.from(template, objectMapper)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<PromptTemplateResponse>>> getList(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {

        Page<PromptTemplate> result = promptTemplateService.getList(
            userDetails.getId(), category, search, PageRequest.of(page, size));

        PageResponse<PromptTemplateResponse> pageResponse = PageResponse.of(
            result.getContent(),
            result.hasNext(),
            result.hasNext() ? String.valueOf(page + 1) : null,
            template -> PromptTemplateResponse.from(template, objectMapper));

        return ResponseEntity.ok(ApiResponse.ok(pageResponse));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PromptTemplateResponse>> getOne(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @PathVariable UUID id) {

        PromptTemplate template = promptTemplateService.getByIdAndUserId(id, userDetails.getId());
        return ResponseEntity.ok(ApiResponse.ok(PromptTemplateResponse.from(template, objectMapper)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<PromptTemplateResponse>> update(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @PathVariable UUID id,
        @RequestBody @Valid UpdatePromptTemplateRequest request) {

        PromptTemplate template = promptTemplateService.update(id, userDetails.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok(PromptTemplateResponse.from(template, objectMapper)));
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<ApiResponse<TestPromptTemplateResponse>> test(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @PathVariable UUID id,
        @RequestBody @Valid TestPromptTemplateRequest request) {

        String parametersJson = toJsonString(request.getParameters());
        TestResult result = promptTemplateService.testTemplate(
            id,
            userDetails.getId(),
            request.getCredentialId(),
            request.getProvider(),
            request.getModel(),
            request.getVariables(),
            parametersJson);

        TestPromptTemplateResponse response = TestPromptTemplateResponse.builder()
            .response(result.response())
            .usage(new TokenUsageDto(result.usage().inputTokens(), result.usage().outputTokens()))
            .durationMs(result.durationMs())
            .renderedPrompt(new RenderedPromptDto(
                result.renderedPrompt().systemMessage(),
                result.renderedPrompt().userMessage()))
            .build();

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
        @AuthenticationPrincipal CustomUserDetails userDetails,
        @PathVariable UUID id) {

        promptTemplateService.delete(id, userDetails.getId());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    private String toJsonString(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
    }
}
