package com.ieum.api.provider.controller;

import com.ieum.api.provider.dto.ProviderListResponse;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "프로바이더", description = "지원 AI 프로바이더 및 모델 정보")
public interface ProviderControllerDocs {

    @Operation(summary = "지원 프로바이더 및 모델 목록")
    ResponseEntity<ApiResponse<ProviderListResponse>> getProviders();
}
