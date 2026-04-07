package com.ieum.api.provider.controller;

import com.ieum.ai.provider.model.ProviderInfo;
import com.ieum.ai.provider.service.ProviderRegistry;
import com.ieum.api.provider.dto.ProviderListResponse;
import com.ieum.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProviderController implements ProviderControllerDocs {

    private final ProviderRegistry providerRegistry;

    @GetMapping("/providers")
    public ResponseEntity<ApiResponse<ProviderListResponse>> getProviders() {
        List<ProviderInfo> providers = providerRegistry.getAllProviders();
        return ResponseEntity.ok(ApiResponse.ok(
                ProviderListResponse.builder().providers(providers).build()
        ));
    }
}
