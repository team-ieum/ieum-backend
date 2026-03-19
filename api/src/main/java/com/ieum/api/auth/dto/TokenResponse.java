package com.ieum.api.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "토큰 응답")
@Getter
@Builder
public class TokenResponse {

    @Schema(description = "액세스 토큰", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String accessToken;

    @Schema(description = "리프레시 토큰", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String refreshToken;

    @Schema(description = "액세스 토큰 만료 시간 (초)", example = "3600")
    private Long expiresIn;
}
