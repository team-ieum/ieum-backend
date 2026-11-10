package com.ieum.api.usage.controller;

import com.ieum.api.usage.dto.TokenUsageResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag(name = "사용량", description = "AI 토큰 사용량 조회")
@SecurityRequirement(name = "BearerAuth")
public interface UsageControllerDocs {

    @Operation(summary = "AI 토큰 사용량 조회",
        description = "기간 내 provider별·모델별 입력/출력/합계 토큰과 호출 수, 날짜×모델 일별 추이를 반환한다. "
            + "provider 3종과 카탈로그 모델은 사용량 0이어도 항상 포함되고, 카탈로그 밖 실사용 모델은 "
            + "inCatalog=false로 뒤에 붙는다. 워크플로우 AI 노드(재시도·fallback 회차 포함)와 채팅을 모두 집계하며 "
            + "기능 배포 시점부터 집계된다. 기간은 최대 1년, 초과하거나 from > to면 400 INVALID_INPUT.")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<TokenUsageResponse>> getTokenUsage(
        CustomUserDetails userDetails,
        @Parameter(description = "시작일(포함, yyyy-MM-dd). 생략 시 이번 달 1일", example = "2026-10-01") LocalDate from,
        @Parameter(description = "종료일(포함, yyyy-MM-dd). 생략 시 오늘", example = "2026-10-31") LocalDate to);
}
