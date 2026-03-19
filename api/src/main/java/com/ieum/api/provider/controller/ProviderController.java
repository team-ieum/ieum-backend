package com.ieum.api.provider.controller;

import com.ieum.ai.provider.model.ProviderInfo;
import com.ieum.ai.provider.service.ProviderRegistry;
import com.ieum.api.provider.dto.ProviderListResponse;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "프로바이더", description = "지원 AI 프로바이더 및 모델 정보")
public class ProviderController {

    private final ProviderRegistry providerRegistry;

    @GetMapping("/providers")
    @Operation(summary = "지원 프로바이더 및 모델 목록")
    public ResponseEntity<ApiResponse<ProviderListResponse>> getProviders() {
        List<ProviderInfo> providers = providerRegistry.getAllProviders();
        return ResponseEntity.ok(ApiResponse.ok(
                ProviderListResponse.builder().providers(providers).build()
        ));
    }
}
