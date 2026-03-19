package com.ieum.api.common;

import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;

@Tag(name = "Health")
public interface HealthControllerDocs {

    @Operation(summary = "헬스 체크")
    @GetMapping("/health")
    ApiResponse<String> health();
}
