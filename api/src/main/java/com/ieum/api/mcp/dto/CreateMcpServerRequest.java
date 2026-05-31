package com.ieum.api.mcp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CreateMcpServerRequest(
        @NotBlank(message = "displayName은 필수입니다.")
        @Size(max = 100, message = "displayName은 100자 이하여야 합니다.")
        String displayName,

        @Size(max = 500, message = "description은 500자 이하여야 합니다.")
        String description,

        @NotBlank(message = "serverUrl은 필수입니다.")
        String serverUrl,

        /** 인증 헤더 (예: {"Authorization": "Bearer ..."}). 선택 사항. */
        Map<String, String> headers
) {
}
