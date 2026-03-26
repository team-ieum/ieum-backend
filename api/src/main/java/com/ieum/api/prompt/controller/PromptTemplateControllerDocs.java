package com.ieum.api.prompt.controller;

import com.ieum.api.prompt.dto.CreatePromptTemplateRequest;
import com.ieum.api.prompt.dto.PromptTemplateResponse;
import com.ieum.api.prompt.dto.UpdatePromptTemplateRequest;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import com.ieum.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag(name = "프롬프트 템플릿", description = "프롬프트 템플릿 관리")
@SecurityRequirement(name = "BearerAuth")
public interface PromptTemplateControllerDocs {

    @Operation(summary = "템플릿 생성")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<PromptTemplateResponse>> create(
        CustomUserDetails userDetails,
        CreatePromptTemplateRequest request);

    @Operation(summary = "템플릿 목록 조회")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<PageResponse<PromptTemplateResponse>>> getList(
        CustomUserDetails userDetails,
        String category,
        String search,
        int page,
        int size);

    @Operation(summary = "템플릿 상세 조회")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<PromptTemplateResponse>> getOne(
        CustomUserDetails userDetails,
        UUID id);

    @Operation(summary = "템플릿 수정")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<PromptTemplateResponse>> update(
        CustomUserDetails userDetails,
        UUID id,
        UpdatePromptTemplateRequest request);

    @Operation(summary = "템플릿 삭제")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<Void>> delete(
        CustomUserDetails userDetails,
        UUID id);
}
