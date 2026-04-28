package com.ieum.api.provider.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "AI 프로바이더 정보")
public record ProviderInfo(
        @Schema(description = "프로바이더 코드", example = "CLAUDE") String provider,
        @Schema(description = "표시 이름", example = "Anthropic Claude") String displayName,
        @Schema(description = "지원 크레덴셜 타입", example = "[\"API_KEY\"]") List<String> credentialTypes,
        @Schema(description = "지원 모델 목록") List<ModelInfo> models
) {}
