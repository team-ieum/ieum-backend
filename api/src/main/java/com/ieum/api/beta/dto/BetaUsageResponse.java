package com.ieum.api.beta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 베타 플랫폼 키 사용량 조회 응답.
 *
 * <p>토큰 원값(사용량/예산)은 절대 노출하지 않는다 — 내부 회계 단위가 바뀌어도 FE 계약이 깨지지 않도록
 * 항상 사용률(%)과 잔여 호출 횟수만 반환한다.
 */
@Schema(description = "베타 사용량 조회 응답")
@Getter
@Builder
public class BetaUsageResponse {

    @Schema(description = "토큰 예산 대비 사용률(%)", example = "12.5")
    private double percentage;

    @Schema(description = "오늘 남은 호출 가능 횟수", example = "18")
    private long dailyCallsRemaining;

    public static BetaUsageResponse of(double percentage, long dailyCallsRemaining) {
        return BetaUsageResponse.builder()
                .percentage(percentage)
                .dailyCallsRemaining(dailyCallsRemaining)
                .build();
    }
}
