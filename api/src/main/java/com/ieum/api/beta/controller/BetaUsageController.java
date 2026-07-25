package com.ieum.api.beta.controller;

import com.ieum.api.beta.dto.BetaUsageResponse;
import com.ieum.api.beta.service.BetaQuotaService;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/beta")
@RequiredArgsConstructor
public class BetaUsageController implements BetaUsageControllerDocs {

    private final BetaQuotaService betaQuotaService;

    @GetMapping("/usage")
    public ResponseEntity<ApiResponse<BetaUsageResponse>> getUsage(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        BetaUsageResponse response = BetaUsageResponse.of(
                betaQuotaService.getTokenUsagePercentage(userDetails.getId()),
                betaQuotaService.getRemainingDailyCalls(userDetails.getId()));

        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
