package com.ieum.api.common;

import com.ieum.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController implements HealthControllerDocs {

    @Override
    public ApiResponse<String> health() {
        return ApiResponse.ok("UP");
    }
}
