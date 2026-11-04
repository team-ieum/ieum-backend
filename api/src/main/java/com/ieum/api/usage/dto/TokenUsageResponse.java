package com.ieum.api.usage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "기간 내 AI 토큰 사용량 — provider → 모델 합계 + 날짜×모델 일별 추이")
public record TokenUsageResponse(
    @Schema(description = "시작일(포함)", example = "2026-10-01") LocalDate from,
    @Schema(description = "종료일(포함)", example = "2026-10-31") LocalDate to,
    @Schema(description = "provider 3종(OPENAI·CLAUDE·GEMINI) — 사용량 0이어도 항상 포함, 카탈로그 순서")
    List<ProviderUsage> providers,
    @Schema(description = "날짜 × 모델 일별 합계. 사용량 있는 날·모델만 포함, 날짜 오름차순")
    List<DailyUsage> daily
) {

    @Schema(description = "provider 합계. 토큰·호출 수는 models의 합이다")
    public record ProviderUsage(
        @Schema(example = "CLAUDE") String provider,
        @Schema(example = "Anthropic Claude") String displayName,
        long inputTokens, long outputTokens, long totalTokens, long callCount,
        @Schema(description = "카탈로그 모델 전부(사용량 0 포함, 카탈로그 순서) 뒤에 카탈로그 밖 실사용 모델")
        List<ModelUsage> models
    ) {}

    @Schema(description = "모델별 합계. 토큰을 보고하지 않은 호출은 호출 수에만 더해진다")
    public record ModelUsage(
        @Schema(example = "claude-sonnet-5-5") String model,
        @Schema(description = "표시 이름. 카탈로그 밖 모델은 모델 ID", example = "Claude Sonnet 5.5") String displayName,
        @JsonProperty("isDefault") @Schema(description = "provider 기본 모델 여부") boolean isDefault,
        @JsonProperty("inCatalog") @Schema(description = "모델 카탈로그(GET /api/v1/providers) 등재 여부") boolean inCatalog,
        long inputTokens, long outputTokens, long totalTokens, long callCount
    ) {}

    public record DailyUsage(
        @Schema(example = "2026-10-01") LocalDate date,
        @Schema(example = "CLAUDE") String provider,
        @Schema(example = "claude-sonnet-5-5") String model,
        long inputTokens, long outputTokens, long totalTokens, long callCount
    ) {}
}
