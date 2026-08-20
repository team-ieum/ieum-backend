package com.ieum.api.provider.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "AI 모델 정보")
public record ModelInfo(
        // allowableValues를 두지 않는다 — ProviderRegistry의 카탈로그를 손으로 베낀 두 번째 목록이라
        // 한쪽만 고치면 스펙이 실재하지 않는 enum을 광고하고, 스펙에서 클라이언트를 생성하는 쪽이
        // 서버가 준 모델 ID를 역직렬화하지 못한다. 목록의 진실은 ProviderRegistry 하나다.
        @Schema(description = "모델 ID", example = "claude-sonnet-4-20250514") String id,
        @Schema(description = "표시 이름", example = "Claude Sonnet 4") String displayName,
        @Schema(description = "지원 기능", example = "[\"text\", \"vision\"]") List<String> capabilities,
        @Schema(description = "최대 출력 토큰", example = "8192") int maxOutputTokens,
        @Schema(description = "컨텍스트 윈도우 크기", example = "200000") int contextWindow
) {}
