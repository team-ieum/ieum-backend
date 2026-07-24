package com.ieum.api.beta.controller;

import com.ieum.api.beta.dto.BetaUsageResponse;
import com.ieum.auth.security.CustomUserDetails;
import com.ieum.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag(name = "베타", description = "베타 플랫폼 키(Gemini) 체험 사용량 조회")
@SecurityRequirement(name = "BearerAuth")
public interface BetaUsageControllerDocs {

    @Operation(summary = "베타 사용량 조회",
        description = "토큰 예산 대비 사용률(%)과 오늘 남은 호출 가능 횟수를 반환한다. "
            + "베타 비자격/미사용 사용자는 0%·전체 잔여 횟수로 응답한다.")
    @PreAuthorize("hasRole('USER')")
    ResponseEntity<ApiResponse<BetaUsageResponse>> getUsage(CustomUserDetails userDetails);
}
