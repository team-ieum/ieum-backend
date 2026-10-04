package com.ieum.api.integration.options;

import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations")
@RequiredArgsConstructor
public class IntegrationOptionController implements IntegrationOptionControllerDocs {

    private final IntegrationOptionService integrationOptionService;

    @Override
    @GetMapping("/{app}/options/{resource}")
    public ResponseEntity<ApiResponse<OptionPage>> getOptions(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable String app,
            @PathVariable String resource,
            @RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.ok(
            integrationOptionService.fetch(userDetails.getId(), app, resource, params)));
    }
}
