package com.ieum.api.provider.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "AI 모델 정보")
public record ModelInfo(
        @Schema(description = "모델 ID", example = "claude-sonnet-4-20250514", allowableValues = {
                "claude-sonnet-4-20250514", "claude-haiku-4-5-20251001",
                "gpt-4o", "gpt-4o-mini",
                "gemini-3.5-flash"
        }) String id,
        @Schema(description = "표시 이름", example = "Claude Sonnet 4") String displayName,
        @Schema(description = "지원 기능", example = "[\"text\", \"vision\"]") List<String> capabilities,
        @Schema(description = "최대 출력 토큰", example = "8192") int maxOutputTokens,
        @Schema(description = "컨텍스트 윈도우 크기", example = "200000") int contextWindow
) {}
